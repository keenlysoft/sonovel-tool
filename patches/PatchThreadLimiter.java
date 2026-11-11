import java.io.*;
import java.nio.file.*;
import java.util.jar.JarFile;

/** Raise VirtualThreadLimiter's 3-minute await; it silently truncated long downloads. */
public final class PatchThreadLimiter {
    private static final String ENTRY = "com/pcdd/sonovel/util/VirtualThreadLimiter.class";
    private static final long OLD_MINUTES = 3L, NEW_MINUTES = 120L;

    public static void main(String[] args) throws IOException {
        byte[] original;
        try (JarFile jar = new JarFile(args[0]); var in = jar.getInputStream(jar.getJarEntry(ENTRY))) {
            original = in.readAllBytes();
        }
        var bytes = new ByteArrayOutputStream(original.length);
        int replacements = 0;
        try (var in = new DataInputStream(new ByteArrayInputStream(original)); var out = new DataOutputStream(bytes)) {
            out.writeInt(in.readInt());
            out.writeInt(in.readInt());
            int count = in.readUnsignedShort();
            out.writeShort(count);
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                out.writeByte(tag);
                switch (tag) {
                    case 1 -> out.writeUTF(in.readUTF());
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> out.writeInt(in.readInt());
                    case 5 -> {
                        long value = in.readLong();
                        if (value == OLD_MINUTES) { value = NEW_MINUTES; replacements++; }
                        out.writeLong(value);
                        i++;
                    }
                    case 6 -> { out.writeLong(in.readLong()); i++; }
                    case 7, 8, 16, 19, 20 -> out.writeShort(in.readUnsignedShort());
                    case 15 -> { out.writeByte(in.readUnsignedByte()); out.writeShort(in.readUnsignedShort()); }
                    default -> throw new IOException("Unknown constant tag " + tag);
                }
            }
            in.transferTo(out);
        }
        if (replacements != 1) throw new IOException("Expected one 3-minute constant, found " + replacements);
        Path target = Path.of(args[1], ENTRY);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes.toByteArray());
    }
}
