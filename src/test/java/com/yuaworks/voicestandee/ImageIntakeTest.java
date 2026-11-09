package com.yuaworks.voicestandee;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageIntakeTest {

    private static byte[] encode(BufferedImage image, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static BufferedImage square(int w, int h, int type) {
        BufferedImage img = new BufferedImage(w, h, type);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static BufferedImage read(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    @Test
    void aSmallPngComesBackAsPng() throws Exception {
        byte[] out = ImageIntake.toStandeePng(encode(square(100, 200, BufferedImage.TYPE_INT_ARGB), "png"));
        BufferedImage image = read(out);
        assertEquals(100, image.getWidth());
        assertEquals(200, image.getHeight());
    }

    @Test
    void jpegAndGifAreAccepted() throws Exception {
        assertEquals(64, read(ImageIntake.toStandeePng(encode(square(64, 64, BufferedImage.TYPE_INT_RGB), "jpg"))).getWidth());
        assertEquals(64, read(ImageIntake.toStandeePng(encode(square(64, 64, BufferedImage.TYPE_INT_RGB), "gif"))).getWidth());
    }

    @Test
    void largePicturesAreShrunkToTheLimit() throws Exception {
        BufferedImage image = read(ImageIntake.toStandeePng(encode(square(2000, 3000, BufferedImage.TYPE_INT_ARGB), "png")));
        assertEquals(ImageIntake.MAX_SIDE, image.getHeight());
        assertEquals(Math.round(2000 * ImageIntake.MAX_SIDE / 3000.0), image.getWidth());
    }

    @Test
    void transparentMarginsAreCutAway() throws Exception {
        BufferedImage img = new BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(100, 50, 80, 120);
        g.dispose();
        BufferedImage out = read(ImageIntake.toStandeePng(encode(img, "png")));
        assertEquals(80, out.getWidth());
        assertEquals(120, out.getHeight());
    }

    @Test
    void aFullyTransparentPictureIsRefused() {
        assertThrows(ImageIntake.Rejected.class,
                () -> ImageIntake.toStandeePng(encode(new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB), "png")));
    }

    @Test
    void notAnImageIsRefused() {
        assertThrows(ImageIntake.Rejected.class, () -> ImageIntake.toStandeePng("this is not a picture".getBytes()));
        assertThrows(ImageIntake.Rejected.class, () -> ImageIntake.toStandeePng(new byte[0]));
    }

    @Test
    void tooSmallIsRefused() {
        assertThrows(ImageIntake.Rejected.class, () -> ImageIntake.toStandeePng(encode(square(8, 8, BufferedImage.TYPE_INT_ARGB), "png")));
    }

    @Test
    void tooManyPixelsIsRefusedBeforeDecoding() throws Exception {
        // 4000 x 4000 = 16 million pixels, a few KB as a flat grey PNG
        byte[] bomb = encode(new BufferedImage(4000, 4000, BufferedImage.TYPE_BYTE_GRAY), "png");
        assertTrue(bomb.length < ImageIntake.MAX_BYTES);
        ImageIntake.Rejected e = assertThrows(ImageIntake.Rejected.class, () -> ImageIntake.toStandeePng(bomb));
        assertTrue(e.getMessage().contains("大きすぎ"));
    }

    @Test
    void tooManyBytesIsRefused() {
        assertThrows(ImageIntake.Rejected.class, () -> ImageIntake.toStandeePng(new byte[ImageIntake.MAX_BYTES + 1]));
    }

    @Test
    void trailingDataAfterTheImageIsNotKept() throws Exception {
        byte[] png = encode(square(50, 50, BufferedImage.TYPE_INT_ARGB), "png");
        byte[] polluted = new byte[png.length + 100];
        System.arraycopy(png, 0, polluted, 0, png.length);
        byte[] out = ImageIntake.toStandeePng(polluted);
        assertTrue(out.length < polluted.length);
    }
}
