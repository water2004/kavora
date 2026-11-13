// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors.
// SPDX-License-Identifier: MIT
#pragma once

#include <concepts>
#include <type_traits>

#include "rtx_resources.h"

namespace dxvk {
  // Borrows the frame and exposes only the resources declared by a pass.
  // Aliased resources register their actual access at the original call site.
  using RaytracingFrame = Resources::RaytracingOutput;

  template<auto Member>
  struct FrameResourceMember {};

  template<auto Member>
  concept RaytracingFrameMember = std::is_member_object_pointer_v<decltype(Member)> &&
    requires(const RaytracingFrame& frame) { frame.*Member; };

  template<auto Member>
  concept RaytracingFrameAccessor = std::is_member_function_pointer_v<decltype(Member)> &&
    requires(const RaytracingFrame& frame) { (frame.*Member)(); };

  template<auto... Members>
    requires ((RaytracingFrameMember<Members> || RaytracingFrameAccessor<Members>) && ...)
  class FrameResourceView {
  public:
    explicit FrameResourceView(const RaytracingFrame& frame)
      : m_pFrame(&frame) { }

    FrameResourceView(const RaytracingFrame&&) = delete;

    template<auto Member>
      requires (std::same_as<FrameResourceMember<Member>, FrameResourceMember<Members>> || ...)
    decltype(auto) get() const {
      if constexpr (RaytracingFrameAccessor<Member>) {
        return (m_pFrame->*Member)();
      } else {
        return (m_pFrame->*Member);
      }
    }

  private:
    const RaytracingFrame* m_pFrame;
  };
}
