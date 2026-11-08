import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

/** Patch one known v1.9.6 constant without recompiling or modifying the input JAR. */
public final class PatchChapterParser {
    private static final String ENTRY = "com/pcdd/sonovel/parse/ChapterParser.class";
    private static final String OLD_PATTERN = ".*[-_]\\d\\.html";
    private static final String NEW_PATTERN = ".*[-_]\\d+\\.html";

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected INPUT_JAR OUTPUT_DIRECTORY");
        }
        byte[] original;
        try (JarFile jar = new JarFile(args[0])) {
            var entry = jar.getJarEntry(ENTRY);
            if (entry == null) throw new IOException("ChapterParser class was not found");
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(original.length + 1);
        int replacements = 0;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(original));
             DataOutputStream output = new DataOutputStream(bytes)) {
            int magic = input.readInt();
            int minor = input.readUnsignedShort();
            int major = input.readUnsignedShort();
            if (magic != 0xCAFEBABE || major != 65) {
                throw new IOException("Expected the bundled Java 21 ChapterParser class (major 65)");
            }
            output.writeInt(magic);
            output.writeShort(minor);
            output.writeShort(major);
            int count = input.readUnsignedShort();
            output.writeShort(count);
            for (int index = 1; index < count; index++) {
                int tag = input.readUnsignedByte();
                output.writeByte(tag);
                switch (tag) {
                    case 1 -> {
                        String value = input.readUTF();
                        if (OLD_PATTERN.equals(value)) {
                            value = NEW_PATTERN;
                            replacements++;
                        }
                        output.writeUTF(value);
                    }
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> output.writeInt(input.readInt());
                    case 5, 6 -> {
                        output.writeLong(input.readLong());
                        index++; // CONSTANT_Long and CONSTANT_Double reserve two slots.
                    }
                    case 7, 8, 16, 19, 20 -> output.writeShort(input.readUnsignedShort());
                    case 15 -> {
                        output.writeByte(input.readUnsignedByte());
                        output.writeShort(input.readUnsignedShort());
                    }
                    default -> throw new IOException("Unsupported constant-pool tag " + tag);
                }
            }
            input.transferTo(output);
        }
        if (replacements != 1) {
            throw new IOException("Expected exactly one pagination pattern; found " + replacements);
        }
        Path target = Path.of(args[1]).resolve(ENTRY);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes.toByteArray());
        System.out.println("Patched ChapterParser pagination to accept multi-digit page numbers");
    }
}
