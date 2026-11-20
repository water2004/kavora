#pragma once

// NV-DXVK start: Verify the native presentation push-constant layout.
#include <cstddef>
// NV-DXVK end

#include "../dxvk/dxvk_device.h"
#include "../dxvk/dxvk_context.h"
// NV-DXVK start: Present using the immutable frame's encoding parameters.
#include "dxvk_display_output.h"
// NV-DXVK end

namespace dxvk {

  /**
   * \brief Gamma control point
   */
  struct DxvkGammaCp {
    uint16_t r, g, b, a;
  };
  
  /**
   * \brief Swap chain blitter
   *
   * Provides common rendering code for blitting
   * rendered images to a swap chain image.
   */
  class DxvkSwapchainBlitter : public RcObject {
    
  public:

    DxvkSwapchainBlitter(const Rc<DxvkDevice>& device);
    ~DxvkSwapchainBlitter();

    /**
     * \brief Records presentation commands
     *
     * \param [in] ctx Context
     * \param [in] dstView Swap chain image view
     * \param [in] srcView Image to present
     * \param [in] dstRect Destination rectangle
     * \param [in] srcRect Back buffer rectangle
     */
    // NV-DXVK start: Accept the host framebuffer's vertical orientation.
    void presentImage(
            DxvkContext*        ctx,
      const Rc<DxvkImageView>&  dstView,
            VkRect2D            dstRect,
      const Rc<DxvkImageView>&  srcView,
            VkRect2D            srcRect,
            bool                bottomUp = false,
      const DxvkDisplayPresent& display = {},
      const Rc<DxvkImageView>&  uiAlpha = nullptr);
    // NV-DXVK end

    /**
     * \brief Sets gamma ramp
     *
     * If the number of control points is non-zero, this
     * will create a texture containing a gamma ramp that
     * will be used for presentation.
     * \param [in] cpCount Number of control points
     * \param [in] cpData Control point data
     */
    void setGammaRamp(
            uint32_t            cpCount,
      const DxvkGammaCp*        cpData);

  private:

    enum BindingIds : uint32_t {
      Image = 0,
      Gamma = 1,
    };

    struct PresenterArgs {
      VkOffset2D srcOffset;
      union {
        VkExtent2D srcExtent;
        VkOffset2D dstOffset;
      };
      // NV-DXVK start: Host framebuffer orientation.
      uint32_t bottomUp;
      uint32_t sourceHeight;
      DxvkDisplayPresentArgs display;
      // NV-DXVK end
    };

    // NV-DXVK start: Match all four GLSL presentation push-constant blocks.
    static_assert(sizeof(PresenterArgs) == 48);
    static_assert(offsetof(PresenterArgs, display) == 24);
    // NV-DXVK end

    Rc<DxvkDevice>      m_device;

    Rc<DxvkShader>      m_fsCopy;
    Rc<DxvkShader>      m_fsBlit;
    Rc<DxvkShader>      m_fsResolve;
    Rc<DxvkShader>      m_vs;

    Rc<DxvkBuffer>      m_gammaBuffer;
    Rc<DxvkImage>       m_gammaImage;
    Rc<DxvkImageView>   m_gammaView;
    uint32_t            m_gammaCpCount = 0;
    bool                m_gammaDirty = false;
    DxvkBufferSliceHandle m_gammaSlice = { };

    Rc<DxvkImage>       m_resolveImage;
    Rc<DxvkImageView>   m_resolveView;

    Rc<DxvkSampler>     m_samplerPresent;
    Rc<DxvkSampler>     m_samplerGamma;

    // NV-DXVK start: Propagate host framebuffer orientation to presentation shaders.
    void draw(
            DxvkContext*        ctx,
      const Rc<DxvkShader>&     fs,
      const Rc<DxvkImageView>&  dstView,
            VkRect2D            dstRect,
      const Rc<DxvkImageView>&  srcView,
            VkRect2D            srcRect,
            bool                bottomUp,
      const DxvkDisplayPresent& display,
      const Rc<DxvkImageView>&  uiAlpha = nullptr);
    // NV-DXVK end

    void resolve(
            DxvkContext*        ctx,
      const Rc<DxvkImageView>&  dstView,
      const Rc<DxvkImageView>&  srcView);

    void updateGammaTexture(DxvkContext* ctx);

    void createSampler();

    void createShaders();

    void createResolveImage(
      const DxvkImageCreateInfo&  info);

    void destroyResolveImage();

  };
  
}
