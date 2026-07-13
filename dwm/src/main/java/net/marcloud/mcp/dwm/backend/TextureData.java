package net.marcloud.mcp.dwm.backend;

/**
 * Raw RGBA pixel data for {@link RenderBackend#uploadTexture}. Neutral value type;
 * the backend copies it to a GPU texture and returns a {@link TextureHandle}.
 */
public record TextureData(int width, int height, byte[] rgba) {

    public TextureData {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("texture dimensions must be > 0");
        }
        if (rgba == null || rgba.length < width * height * 4) {
            throw new IllegalArgumentException("rgba must hold width*height*4 bytes");
        }
    }
}
