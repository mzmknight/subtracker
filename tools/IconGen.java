import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.GlyphVector;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * The app icon, drawn once and emitted for both platforms.
 *
 * One generator rather than two sets of hand-drawn assets: an Android adaptive
 * icon and a Windows .ico that drifted apart would be two logos for one app.
 *
 * The mark is a pound sign inside an open circular arrow — money that comes
 * round again, which is the whole subject of the app. Drawn as a filled glyph
 * outline rather than as text so it does not depend on the font metrics of
 * whatever machine builds it.
 *
 *   javac -d out tools/IconGen.java && java -cp out IconGen
 */
public final class IconGen {

    // The app's own palette, so the icon and the dashboard are the same thing.
    private static final Color NAVY = new Color(0x0C1220);
    private static final Color NAVY_LIFT = new Color(0x162030);
    private static final Color EMERALD = new Color(0x3DDC91);

    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path android = root.resolve("apps/androidApp/src/main/res");
        Path desktop = root.resolve("apps/desktopApp/icons");

        // Adaptive icon foreground. Android masks these to whatever shape the
        // launcher uses, so the artwork has to sit inside the middle 66%.
        Map<String, Integer> densities = new LinkedHashMap<>();
        densities.put("mipmap-mdpi", 108);
        densities.put("mipmap-hdpi", 162);
        densities.put("mipmap-xhdpi", 216);
        densities.put("mipmap-xxhdpi", 324);
        densities.put("mipmap-xxxhdpi", 432);

        for (Map.Entry<String, Integer> e : densities.entrySet()) {
            String density = e.getKey().substring("mipmap-".length());
            int at108dp = e.getValue();

            Path mipmap = android.resolve(e.getKey());
            Files.createDirectories(mipmap);
            ImageIO.write(foreground(at108dp), "png",
                mipmap.resolve("ic_launcher_foreground.png").toFile());
            // A flat 48dp icon beside the adaptive one. minSdk is 26 so every
            // device can use the adaptive version, but some launchers and the
            // Play/installer UI still reach for a plain bitmap.
            ImageIO.write(composed(Math.round(at108dp * 48f / 108f)), "png",
                mipmap.resolve("ic_launcher.png").toFile());

            // Notification icons belong in drawable, not mipmap — mipmap exists
            // so launcher icons survive density stripping, which does not apply
            // here. White silhouette only: Android tints these and throws the
            // colour away, so anything else arrives as a white blob.
            Path drawable = android.resolve("drawable-" + density);
            Files.createDirectories(drawable);
            ImageIO.write(notificationIcon(Math.round(at108dp * 24f / 108f)), "png",
                drawable.resolve("ic_notification.png").toFile());
            // Themed icons (Android 13+): the launcher recolours this to match
            // the wallpaper, so it must be a flat silhouette with no palette of
            // its own — the emerald version would come out as a solid slab.
            ImageIO.write(monochrome(at108dp), "png",
                drawable.resolve("ic_launcher_monochrome.png").toFile());
        }

        Files.createDirectories(desktop);
        int[] icoSizes = {16, 24, 32, 48, 64, 128, 256};
        writeIco(desktop.resolve("subtracker.ico").toFile(), icoSizes);
        // A PNG too: jpackage wants .ico, but a plain image is handy for
        // anywhere else the app has to be represented.
        ImageIO.write(composed(512), "png", desktop.resolve("subtracker.png").toFile());

        // On the classpath, for the *window* icon.
        //
        // The .ico above is the executable's, which Windows uses for the file,
        // the shortcut and the Start menu — but a running app's taskbar button
        // shows the icon of its window, and the JVM's default for that is the
        // Java coffee cup. Without this the app ships with a correct icon
        // everywhere except the one place it is looked at most.
        Path resources = root.resolve("apps/desktopApp/src/main/resources");
        Files.createDirectories(resources);
        ImageIO.write(composed(256), "png", resources.resolve("subtracker-icon.png").toFile());

        System.out.println("wrote android foregrounds + notification icons to " + android);
        System.out.println("wrote subtracker.ico and subtracker.png to " + desktop);

        // `--preview` renders the sizes it will actually be seen at. An icon
        // judged only at 512px is an icon nobody has looked at properly: the
        // small ones are where a too-fine stroke or a crowded glyph shows up.
        if (args.length > 0 && args[0].equals("--preview")) {
            Path preview = root.resolve("tools/preview");
            Files.createDirectories(preview);
            for (int s : new int[]{32, 48, 72, 96, 192}) {
                ImageIO.write(composed(s), "png", preview.resolve("icon-" + s + ".png").toFile());
            }
            System.out.println("wrote previews to " + preview);
        }
    }

    /** The full icon including its own background — what Windows expects. */
    private static BufferedImage composed(int size) {
        BufferedImage img = blank(size);
        Graphics2D g = draw(img);

        double radius = size * 0.22;
        g.setPaint(new java.awt.GradientPaint(0, 0, NAVY_LIFT, 0, size, NAVY));
        g.fill(new RoundRectangle2D.Double(0, 0, size, size, radius, radius));

        mark(g, size, size * 0.62, EMERALD);
        g.dispose();
        return img;
    }

    /** Transparent, with the mark sized for the adaptive icon's safe zone. */
    private static BufferedImage foreground(int size) {
        BufferedImage img = blank(size);
        Graphics2D g = draw(img);
        mark(g, size, size * 0.50, EMERALD);
        g.dispose();
        return img;
    }

    /** The same shape, flat white, for the launcher to recolour. */
    private static BufferedImage monochrome(int size) {
        BufferedImage img = blank(size);
        Graphics2D g = draw(img);
        mark(g, size, size * 0.50, Color.WHITE);
        g.dispose();
        return img;
    }

    /**
     * The mark: an open circular arrow with a pound sign inside it.
     *
     * [extent] is the width the whole thing should occupy, centred — which is
     * what lets the same drawing be sized for a Windows tile and for the much
     * tighter safe zone of an Android adaptive icon.
     */
    private static void mark(Graphics2D g, int size, double extent, Color colour) {
        double c = size / 2.0;
        double r = extent / 2.0;
        double stroke = extent * 0.155;

        g.setColor(colour);
        g.setStroke(new BasicStroke((float) stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        // Open at the top right, so the arrowhead reads as movement rather than
        // as a closed ring.
        double startDeg = 55;
        double sweepDeg = 285;
        g.draw(new Arc2D.Double(c - r, c - r, 2 * r, 2 * r, startDeg, sweepDeg, Arc2D.OPEN));

        // Arrowhead on the tail end of the sweep, pointing along the tangent.
        double endDeg = startDeg + sweepDeg;
        double rad = Math.toRadians(endDeg);
        double px = c + r * Math.cos(rad);
        double py = c - r * Math.sin(rad);
        // The point traces (cos θ, −sin θ) as θ increases, so the direction of
        // travel is its derivative, (−sin θ, −cos θ). Using (sin θ, cos θ) — the
        // obvious-looking version — is exactly backwards, and draws an arrowhead
        // pointing back the way the arc came.
        double tx = -Math.sin(rad);
        double ty = -Math.cos(rad);
        double head = stroke * 1.15;

        Path2D.Double arrow = new Path2D.Double();
        arrow.moveTo(px + tx * head, py + ty * head);
        arrow.lineTo(px - ty * head * 0.85 - tx * head * 0.35, py + tx * head * 0.85 - ty * head * 0.35);
        arrow.lineTo(px + ty * head * 0.85 - tx * head * 0.35, py - tx * head * 0.85 - ty * head * 0.35);
        arrow.closePath();
        g.fill(arrow);

        drawGlyph(g, "£", c, c, extent * 0.46);
    }

    /** White on transparent, for the notification status bar. */
    private static BufferedImage notificationIcon(int size) {
        BufferedImage img = blank(size);
        Graphics2D g = draw(img);
        g.setColor(Color.WHITE);
        double c = size / 2.0;
        double extent = size * 0.82;
        double r = extent / 2.0;
        g.setStroke(new BasicStroke((float) (extent * 0.16), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(c - r, c - r, 2 * r, 2 * r, 55, 285, Arc2D.OPEN));
        g.fill(new Ellipse2D.Double(c - extent * 0.13, c - extent * 0.13, extent * 0.26, extent * 0.26));
        g.dispose();
        return img;
    }

    /**
     * Draws text as a filled outline, centred on its own ink rather than on the
     * font's line box — otherwise the glyph sits visibly high, because the box
     * reserves room for descenders the pound sign does not have.
     */
    private static void drawGlyph(Graphics2D g, String text, double cx, double cy, double targetHeight) {
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, 100);
        GlyphVector gv = font.createGlyphVector(g.getFontRenderContext(), text);
        Rectangle2D ink = gv.getVisualBounds();

        double scale = targetHeight / ink.getHeight();
        java.awt.geom.AffineTransform at = new java.awt.geom.AffineTransform();
        at.translate(cx, cy);
        at.scale(scale, scale);
        at.translate(-ink.getCenterX(), -ink.getCenterY());

        g.fill(at.createTransformedShape(gv.getOutline()));
    }

    private static BufferedImage blank(int size) {
        return new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    }

    private static Graphics2D draw(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    /**
     * An .ico containing PNG-compressed entries.
     *
     * Java has no ICO writer, and the format is simple enough not to warrant a
     * dependency: a six-byte header, a sixteen-byte directory entry per image,
     * then the images themselves. PNG payloads are understood by everything
     * since Vista, and keep a 256px entry from costing 256 KB of raw bitmap.
     */
    private static void writeIco(File target, int[] sizes) throws IOException {
        byte[][] pngs = new byte[sizes.length][];
        for (int i = 0; i < sizes.length; i++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(composed(sizes[i]), "png", out);
            pngs[i] = out.toByteArray();
        }

        int offset = 6 + 16 * sizes.length;
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(target))) {
            out.write(le16(0));               // reserved
            out.write(le16(1));               // type: icon
            out.write(le16(sizes.length));

            for (int i = 0; i < sizes.length; i++) {
                // 0 means 256 in a single byte, which is the whole reason the
                // format tops out there.
                out.write(sizes[i] >= 256 ? 0 : sizes[i]);
                out.write(sizes[i] >= 256 ? 0 : sizes[i]);
                out.write(0);                 // palette size: none
                out.write(0);                 // reserved
                out.write(le16(1));           // colour planes
                out.write(le16(32));          // bits per pixel
                out.write(le32(pngs[i].length));
                out.write(le32(offset));
                offset += pngs[i].length;
            }
            for (byte[] png : pngs) out.write(png);
        }
    }

    private static byte[] le16(int value) {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array();
    }

    private static byte[] le32(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }
}
