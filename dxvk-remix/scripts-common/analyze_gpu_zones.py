#!/usr/bin/env python3
"""Audit nested GPU timestamp zones without conflating identical names.

Usage: python scripts-common/analyze_gpu_zones.py /path/to/current-profile/gpu.csv --output results/zones.json
No queue/context IDs exist in the supplied export. This script requires laminar,
non-overlapping root intervals, rejects partial overlap, and reports equal-boundary
ambiguities rather than claiming an exact GPU critical-path attribution.
"""
from __future__ import annotations
import argparse
import bisect
import csv
import json
import statistics
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

@dataclass
class Zone:
    name: str
    start: int
    duration: int
    row: int = 0
    children: list['Zone'] = field(default_factory=list)
    @property
    def end(self) -> int:
        return self.start + self.duration

def tree(root: Zone, events: list[Zone]) -> tuple[Zone, int]:
    root.children = []
    stack = [root]
    equal_intervals = 0
    for zone in sorted(events, key=lambda z: (z.start, -z.duration, z.row)):
        zone.children = []
        if zone.start < root.start or zone.end > root.end:
            raise ValueError(f'Zone outside root: {zone.name}, row {zone.row}')
        while len(stack) > 1 and zone.start >= stack[-1].end:
            stack.pop()
        parent = stack[-1]
        if zone.end > parent.end:
            raise ValueError(f'Partially overlapping zones: {parent.name} / {zone.name}; need queue IDs')
        if zone.start == parent.start and zone.end == parent.end:
            equal_intervals += 1
        parent.children.append(zone)
        stack.append(zone)
    return root, equal_intervals

def visit(zone: Zone, prefix: tuple[str, ...] = ()):
    path = prefix + (zone.name,)
    exclusive = zone.duration - sum(child.duration for child in zone.children)
    if exclusive < 0:
        raise ValueError(f'Invalid child accounting: {path}')
    yield path, zone.duration, exclusive
    for child in zone.children:
        yield from visit(child, path)

def analyze(path: Path, frames: int = 360, root_name: str = 'InjectRTX') -> dict:
    if frames <= 0:
        raise ValueError('frames must be positive')
    events = []
    with path.open(newline='', encoding='utf-8-sig') as stream:
        reader = csv.DictReader(stream)
        required = {'name', 'gpu_start_ns', 'duration_ns'}
        if not required.issubset(reader.fieldnames or []):
            raise ValueError(f'Missing fields: {required}')
        for i, row in enumerate(reader, 2):
            z = Zone(row['name'], int(row['gpu_start_ns']), int(row['duration_ns']), i)
            if z.duration < 0:
                raise ValueError(f'Negative duration at CSV row {i}')
            events.append(z)
    roots = sorted((z for z in events if z.name == root_name), key=lambda z: z.start)[-frames:]
    if len(roots) != frames:
        raise ValueError(f'Requested {frames} roots, found {len(roots)}')
    for previous, current in zip(roots, roots[1:]):
        if previous.end > current.start:
            raise ValueError('Overlapping root intervals: cannot infer a single timeline')
    starts = [z.start for z in roots]
    root_rows = {z.row for z in roots}
    groups: list[list[Zone]] = [[] for _ in roots]
    crossing = []
    for z in events:
        if z.row in root_rows:
            continue
        i = bisect.bisect_right(starts, z.start) - 1
        if i < 0 or z.start >= roots[i].end:
            continue
        if z.end > roots[i].end:
            crossing.append({'name': z.name, 'csv_row': z.row})
        else:
            groups[i].append(z)
    if crossing:
        raise ValueError(f'Zones crossing root boundaries: {crossing[:10]}')
    per_path = defaultdict(lambda: {'inclusive': [0] * frames, 'exclusive': [0] * frames, 'calls': [0] * frames})
    ambiguous = 0
    for i, (root, children) in enumerate(zip(roots, groups)):
        _, equal_count = tree(root, children)
        ambiguous += equal_count
        for p, inc, exc in visit(root):
            record = per_path[p]
            record['inclusive'][i] += inc
            record['exclusive'][i] += exc
            record['calls'][i] += 1
    rows = []
    for p, values in sorted(per_path.items()):
        calls = sum(values['calls'])
        rows.append({
            'path': ' / '.join(p), 'calls': calls,
            'min_calls_per_frame': min(values['calls']), 'max_calls_per_frame': max(values['calls']),
            'inclusive_ms_per_frame': statistics.mean(values['inclusive']) / 1e6,
            'inclusive_ms_per_call': sum(values['inclusive']) / calls / 1e6,
            'unlabelled_or_exclusive_ms_per_frame': statistics.mean(values['exclusive']) / 1e6,
            'per_frame_inclusive_ms': [v / 1e6 for v in values['inclusive']],
        })
    return {'source': str(path), 'frames': frames, 'root': root_name,
            'equal_boundary_ambiguities': ambiguous,
            'scope': 'Timestamp nesting inferred from laminar intervals; no queue IDs. Exclusive means parent minus instrumented children, NOT pure shader work.',
            'rows': rows}

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('csv', type=Path)
    parser.add_argument('--frames', type=int, default=360)
    parser.add_argument('--root', default='InjectRTX')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = analyze(args.csv, args.frames, args.root)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    csv_output = args.output.with_suffix('.csv')
    rows = [{k: v for k, v in row.items() if not k.startswith('per_frame')} for row in result['rows']]
    with csv_output.open('w', newline='', encoding='utf-8') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print(json.dumps({'frames': result['frames'], 'paths': len(rows), 'equal_boundary_ambiguities': result['equal_boundary_ambiguities'], 'json': str(args.output), 'csv': str(csv_output)}, ensure_ascii=False))

if __name__ == '__main__':
    main()
