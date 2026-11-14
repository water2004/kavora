// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors.
// SPDX-License-Identifier: MIT
#pragma once

#include <concepts>
#include <type_traits>
#include <utility>

namespace dxvk::frame {
  // Expand the stage's ordered list. Access to aliased resources stays in each pass.
  template<auto... Steps, typename Context, typename Frame, typename... Args>
    requires (std::invocable<decltype(Steps), Context&, Frame&, const Args&...> && ...)
  __forceinline void record(Context& ctx, Frame& frame, const Args&... args) {
    (Steps(ctx, frame, args...), ...);
  }

  template<auto First, auto Last, auto... Steps>
  consteval bool ordered() {
    enum State { BeforeFirst, AfterFirst, AfterLast };
    State state = BeforeFirst;
    bool valid = true;
    ([&] {
      using Step = std::integral_constant<decltype(Steps), Steps>;
      if constexpr (std::same_as<Step, std::integral_constant<decltype(First), First>>) {
        valid = valid && state == BeforeFirst;
        state = AfterFirst;
      } else if constexpr (std::same_as<Step, std::integral_constant<decltype(Last), Last>>) {
        valid = valid && state == AfterFirst;
        state = AfterLast;
      }
    }(), ...);
    return valid && state == AfterLast;
  }

  template<auto... Steps>
  struct Schedule {
    template<auto First, auto Last>
    static constexpr bool orders = ordered<First, Last, Steps...>();

    template<typename Context, typename Frame, typename... Args>
    static __forceinline void record(Context& ctx, Frame& frame, const Args&... args) {
      frame::record<Steps...>(ctx, frame, args...);
    }
  };

  template<auto Value, typename Steps>
  struct Choice : Steps {
    static constexpr auto value = Value;
  };

  // Named alternatives share one output contract. Select one before recording its steps.
  template<auto Otherwise, typename... Choices, typename Key, typename Context, typename Frame>
    requires (std::same_as<std::remove_cv_t<decltype(Choices::value)>, Key> && ...)
  __forceinline void choose(Key key, Context& ctx, Frame& frame) {
    if (!((key == Choices::value && (Choices::record(ctx, frame), true)) || ...)) {
      frame::record<Otherwise>(ctx, frame);
    }
  }

  template<auto GetPass, auto Dispatch, typename Inputs>
  struct BorrowedPass {
    template<typename Context>
    using Pass = std::invoke_result_t<decltype(GetPass), decltype(std::declval<Context&>().getCommonObjects())>;

    template<typename Context, typename Frame>
      requires std::constructible_from<Inputs, const Frame&> &&
        (std::invocable<decltype(Dispatch), Pass<Context>, Context&, Inputs> ||
         std::invocable<decltype(Dispatch), Pass<Context>, Context*, Inputs>)
    __forceinline void operator()(Context& ctx, const Frame& frame) const {
      auto& pass = (ctx.getCommonObjects()->*GetPass)();
      if constexpr (std::invocable<decltype(Dispatch), decltype(pass), Context&, Inputs>) {
        (pass.*Dispatch)(ctx, Inputs(frame));
      } else {
        (pass.*Dispatch)(&ctx, Inputs(frame));
      }
    }
  };
}
