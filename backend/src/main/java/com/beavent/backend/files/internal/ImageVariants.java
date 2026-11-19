package com.beavent.backend.files.internal;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/**
 * Re-encodes public images (which drops EXIF/GPS and any embedded payload) and derives smaller widths for lists and
 * phones on slow connections.
 */
final class ImageVariants {

    static final int MAX_PIXELS = 40_000_000;
    static final int MAX_EDGE = 2560;
    static final Map<String, Integer> WIDTHS = Map.of("w320", 320, "w960", 960);

    record Processed(byte[] original, int width, int height, Map<String, byte[]> variants) {}

    static final class UnreadableImage extends Exception {
        UnreadableImage(String message) {
            super(message);
        }
    }

    private ImageVariants() {}

    static Processed process(byte[] content, String contentType) throws UnreadableImage {
        BufferedImage image = read(content);
        String format = "image/png".equals(contentType) ? "png" : "jpeg";
        BufferedImage original = scaleToFit(image, MAX_EDGE);
        Map<String, byte[]> variants = new LinkedHashMap<>();
        WIDTHS.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .filter(entry -> image.getWidth() > entry.getValue())
                .forEach(entry -> variants.put(entry.getKey(), encode(scaleToWidth(image, entry.getValue()), format)));
        return new Processed(encode(original, format), original.getWidth(), original.getHeight(), variants);
    }

    private static BufferedImage read(byte[] content) throws UnreadableImage {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new UnreadableImage("not a supported image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                // Checked before decoding: a tiny file can claim a huge canvas (decompression bomb).
                if (pixels <= 0 || pixels > MAX_PIXELS) {
                    throw new UnreadableImage("image too large: " + pixels + " pixels");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            throw new UnreadableImage("unreadable image: " + e.getMessage());
        }
    }

    private static BufferedImage scaleToFit(BufferedImage image, int maxEdge) {
        int longest = Math.max(image.getWidth(), image.getHeight());
        return longest <= maxEdge ? image : scaleToWidth(image, (int) ((long) image.getWidth() * maxEdge / longest));
    }

    private static BufferedImage scaleToWidth(BufferedImage image, int width) {
        int height = Math.max(1, (int) Math.round((double) image.getHeight() * width / image.getWidth()));
        boolean alpha = image.getColorModel().hasAlpha();
        BufferedImage scaled =
                new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(image, 0, 0, width, height, null);
        graphics.dispose();
        return scaled;
    }

    private static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (format.equals("png")) {
                ImageIO.write(image, "png", bytes);
                return bytes.toByteArray();
            }
            BufferedImage rgb = image;
            if (image.getType() != BufferedImage.TYPE_INT_RGB) {
                rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = rgb.createGraphics();
                graphics.drawImage(image, 0, 0, java.awt.Color.WHITE, null);
                graphics.dispose();
            }
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(0.85f);
            try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
                writer.setOutput(output);
                writer.write(null, new IIOImage(rgb, null, null), params);
            } finally {
                writer.dispose();
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("image encoding failed", e);
        }
    }
}
