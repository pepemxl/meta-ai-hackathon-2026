// Makes the setup QR the app scans to configure the analysis system, with
// ZXing (the same library the app reads it with).
//
//   java -cp <zxing core-3.5.3.jar> tools/SetupQr.java OUT.png SERVER EMAIL_VAR [PASSWORD_VAR]
//
// EMAIL_VAR and PASSWORD_VAR are the NAMES of environment variables that hold
// the email and the password: the password never goes on the command line
// (it would stay in the shell history) and is never printed. Without
// PASSWORD_VAR the QR has no password and the coach types it. The QR holds
// {"pp_coach_tracker":"setup","v":1,"server","email","password"}
// (app/src/main/java/com/ppcoach/tracker/data/SetupQr.kt), and it is read
// back before saying it is done. A QR with a password is a key.
//
// make.bat setupqr runs it with the jar Gradle already downloaded.

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.imageio.ImageIO;

public class SetupQr {
    static final int SIZE = 720;

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    static String env(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v.trim();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: SetupQr OUT.png SERVER EMAIL_VAR [PASSWORD_VAR]");
            System.exit(2);
        }
        String server = args[1].trim().replaceAll("/+$", "");
        if (!server.startsWith("http://") && !server.startsWith("https://")) fail("SERVER must start with http:// or https://");
        String email = env(args[2]);
        if (!email.contains("@")) fail(args[2] + " has no email");
        String password = args.length > 3 ? env(args[3]) : "";
        if (args.length > 3 && password.isEmpty()) fail(args[3] + " is empty or not set");

        String text = "{\"pp_coach_tracker\":\"setup\",\"v\":1,\"server\":" + quote(server) + ",\"email\":" + quote(email)
            + (password.isEmpty() ? "" : ",\"password\":" + quote(password)) + "}";
        Map<EncodeHintType, Object> hints = Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET, "UTF-8", EncodeHintType.MARGIN, 4);
        BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, SIZE, SIZE, hints);
        BufferedImage img = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < m.getHeight(); y++) for (int x = 0; x < m.getWidth(); x++) img.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xFFFFFF);
        File out = new File(args[0]);
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);

        BufferedImage read = ImageIO.read(out);
        int[] pixels = read.getRGB(0, 0, read.getWidth(), read.getHeight(), null, 0, read.getWidth());
        String back = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(read.getWidth(), read.getHeight(), pixels))),
            Map.of(DecodeHintType.POSSIBLE_FORMATS, java.util.List.of(BarcodeFormat.QR_CODE), DecodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name())).getText();
        if (!text.equals(back)) fail("the QR written does not read back the same: not usable");
        System.out.println(out + ": setup QR for " + email + " on " + server + ", " + (password.isEmpty() ? "without" : "with") + " password; it reads back fine");
    }

    static void fail(String msg) {
        System.err.println(msg);
        System.exit(1);
    }
}
