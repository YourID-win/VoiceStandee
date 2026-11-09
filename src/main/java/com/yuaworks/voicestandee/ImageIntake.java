package com.yuaworks.voicestandee;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Turns whatever a person uploads into one clean PNG, or refuses it with a reason that can be shown to them.
 *
 * <p>The picture is always decoded and drawn again, never stored as it came: that drops metadata and any trailing data, and
 * a file that merely pretends to be an image cannot get through. Size is checked before decoding, so a small file that
 * would blow up into a huge bitmap is refused early.
 */
final class ImageIntake {

    static final int MAX_BYTES = 3_000_000;
    static final int MAX_PIXELS = 12_000_000;
    static final int MAX_SIDE = 1024;
    private static final int MIN_SIDE = 16;
    private static final int MAX_OUTPUT_BYTES = 4_000_000;
    private static final Set<String> FORMATS = Set.of("png", "jpeg", "gif");

    /** The person-facing reason why a picture was refused. */
    static final class Rejected extends Exception {
        Rejected(String message) {
            super(message);
        }
    }

    private ImageIntake() {
    }

    static byte[] toStandeePng(byte[] data) throws Rejected {
        if (data.length == 0) {
            throw new Rejected("画像が空です。");
        }
        if (data.length > MAX_BYTES) {
            throw new Rejected("ファイルが大きすぎます(" + MAX_BYTES / 1_000_000 + "MBまで)。");
        }
        BufferedImage image = decode(data);
        BufferedImage cropped = cropTransparentMargins(toArgb(image));
        BufferedImage fitted = fit(cropped, MAX_SIDE);
        byte[] png = encode(fitted);
        if (png.length > MAX_OUTPUT_BYTES) {
            throw new Rejected("画像が複雑すぎます。もう少し小さい画像にしてください。");
        }
        return png;
    }

    private static BufferedImage decode(byte[] data) throws Rejected {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new Rejected("画像として読めませんでした。PNG / JPG / GIF にしてください。");
            }
            ImageReader reader = readers.next();
            try {
                if (!FORMATS.contains(reader.getFormatName().toLowerCase(Locale.ROOT))) {
                    throw new Rejected("対応しているのは PNG / JPG / GIF です。");
                }
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w < MIN_SIDE || h < MIN_SIDE) {
                    throw new Rejected("画像が小さすぎます(" + MIN_SIDE + "px以上)。");
                }
                if ((long) w * h > MAX_PIXELS) {
                    throw new Rejected("画像の縦横が大きすぎます(" + w + "×" + h + ")。縮小してからもう一度お願いします。");
                }
                return reader.read(0); // a GIF gives its first frame
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            throw new Rejected("画像として読めませんでした。PNG / JPG / GIF にしてください。");
        }
    }

    private static BufferedImage toArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) {
            return src;
        }
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /** Cuts away fully transparent margins, so that pictures with different empty borders still come out the same size. */
    private static BufferedImage cropTransparentMargins(BufferedImage src) throws Rejected {
        int w = src.getWidth();
        int h = src.getHeight();
        int minX = w;
        int minY = h;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((src.getRGB(x, y) >>> 24) > 8) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) {
            throw new Rejected("画像が完全に透明です。");
        }
        if (maxX - minX + 1 < MIN_SIDE || maxY - minY + 1 < MIN_SIDE) {
            throw new Rejected("絵の部分が小さすぎます。");
        }
        if (minX == 0 && minY == 0 && maxX == w - 1 && maxY == h - 1) {
            return src;
        }
        return src.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static BufferedImage fit(BufferedImage src, int max) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= max && h <= max) {
            return src;
        }
        double scale = Math.min((double) max / w, (double) max / h);
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage current = src;
        while (current.getWidth() / 2 >= nw && current.getHeight() / 2 >= nh) {
            current = scale(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        return scale(current, nw, nh);
    }

    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static byte[] encode(BufferedImage image) throws Rejected {
        // getSubimage shares pixels with the larger image; copy so that only the cut part is written and kept
        BufferedImage own = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = own.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(own, "png", out)) {
                throw new Rejected("画像を変換できませんでした。");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new Rejected("画像を変換できませんでした。");
        }
    }
}
