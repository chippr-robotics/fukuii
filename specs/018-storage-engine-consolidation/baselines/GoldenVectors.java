import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.zip.CRC32C;

/** Spec 018 T014: rebuilds the three BlockDiff golden vectors from data-model.md section 4.3 and re-derives each CRC32C. */
public class GoldenVectors {
  static byte[] rep(int b, int n) { byte[] a = new byte[n]; java.util.Arrays.fill(a, (byte) b); return a; }
  static byte[] hex(String s) { return HexFormat.of().parseHex(s.replace(" ", "")); }

  static byte[] vector(int flags, long number, int hash, int parent, int pre, int post, byte[] body) throws Exception {
    var o = new ByteArrayOutputStream();
    o.write(new byte[] {0x01, 0x01, (byte) flags});                  // version, kind, flags
    for (int i = 7; i >= 0; i--) o.write((int) (number >>> (8 * i)) & 0xff); // block number, 8 bytes big-endian
    o.write(rep(hash, 32)); o.write(rep(parent, 32)); o.write(rep(pre, 32)); o.write(rep(post, 32));
    o.write(body);                                                   // entry count and entries
    var c = new CRC32C(); byte[] payload = o.toByteArray(); c.update(payload);
    long v = c.getValue();
    for (int i = 3; i >= 0; i--) o.write((int) (v >>> (8 * i)) & 0xff); // CRC32C, 4 bytes big-endian
    return o.toByteArray();
  }

  public static void main(String[] args) throws Exception {
    var check = new CRC32C(); check.update("123456789".getBytes(StandardCharsets.US_ASCII));
    System.out.printf("check value crc32c(\"123456789\") = %08x (expected e3069283)%n", check.getValue());

    var e2 = new ByteArrayOutputStream();
    e2.write(hex("01 02 21")); e2.write(rep(0xaa, 32)); e2.write(0x1a);            // count, tag, klen 33, key = aa*32 ++ 1a
    e2.write(hex("01 25 e4 82 00 ab a0")); e2.write(rep(0xbb, 32));               // prior present, 37 bytes
    e2.write(0x00);                                                                // next absent

    byte[][] v = {
      vector(0x00, 1, 0x11, 0x22, 0x33, 0x44, hex("01 01 01 00 00 01 03 c2 01 02")),
      vector(0x00, 2, 0x55, 0x11, 0x44, 0x66, e2.toByteArray()),
      vector(0x02, 3, 0x77, 0x55, 0x66, 0x88, hex("00")),
    };
    String[] expected = {"153 8d0afd3e", "219 707894bf", "144 ae4e831f"};
    Path out = Path.of(args.length > 0 ? args[0] : ".");
    boolean ok = true;
    for (int i = 0; i < 3; i++) {
      String h = HexFormat.of().formatHex(v[i]);
      String got = v[i].length + " " + h.substring(h.length() - 8);
      boolean m = got.equals(expected[i]);
      ok &= m;
      System.out.printf("vector %d: %s (expected %s) %s%n", i + 1, got, expected[i], m ? "MATCH" : "MISMATCH");
      Files.writeString(out.resolve("blockdiff-golden-v" + (i + 1) + ".hex"), h + "\n");
    }
    System.exit(ok ? 0 : 1);
  }
}
