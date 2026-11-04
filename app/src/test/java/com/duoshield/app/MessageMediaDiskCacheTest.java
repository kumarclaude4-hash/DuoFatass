package com.duoshield.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Source-level regression test for S08-H3. Decrypted B2 media and decrypted
 * inline thumbnails must never enter Glide's persistent disk cache.
 *
 * <p>Legacy Firebase Storage URLs and public link-preview images intentionally
 * retain their normal cache policy; this assertion targets only the byte-array
 * variables populated from decrypted/cached private B2 media.
 */
public class MessageMediaDiskCacheTest {

    @Test
    public void everyPrivateMediaByteArrayLoadDisablesDiskCaching() throws IOException {
        Path path = Paths.get("src/main/java/com/duoshield/app/ui/MessageAdapter.java");
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);

        Pattern privateLoad = Pattern.compile(
                "Glide\\.with\\(ctx\\)\\.load\\((?:cachedThumb|stamp|jpegBytes|cached|plainBytes|slotStamp)\\)"
                        + "([^;]*?)\\.into\\(",
                Pattern.DOTALL);
        Matcher matcher = privateLoad.matcher(source);
        int count = 0;
        while (matcher.find()) {
            count++;
            assertTrue("Private decrypted bytes must bypass Glide disk cache: " + matcher.group(),
                    matcher.group(1).contains("diskCacheStrategy(DiskCacheStrategy.NONE)"));
        }
        assertEquals("Expected every B2 byte-array request to be covered", 11, count);
    }
}
