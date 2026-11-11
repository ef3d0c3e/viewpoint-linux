// SPDX-FileCopyrightText: 2026 ef3d0c3e
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import me.zed_0xff.zombie_buddy.KnownAuthors;
import me.zed_0xff.zombie_buddy.SteamWorkshop;
import me.zed_0xff.zombie_buddy.ZBSVerifier;

/**
 * Checks a .zbs with ZombieBuddy's own ZBSVerifier, offline: the public key is handed in as
 * a known author, where the game would read it from the Steam profile instead.
 *
 *   java -cp ZombieBuddy.jar ZbsVerify.java <jar> <key.pub>
 */
public final class ZbsVerify {
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        Path zbs = jar.resolveSibling(jar.getFileName() + ".zbs");
        String steamId = Files.readAllLines(zbs).get(1).substring("SteamID64:".length()).trim();

        byte[] blob = Base64.getDecoder().decode(Files.readString(Path.of(args[1])).trim().split("\\s+")[1]);
        String key = HexFormat.of().formatHex(Arrays.copyOfRange(blob, blob.length - 32, blob.length));

        KnownAuthors.AuthorEntry author = new KnownAuthors.AuthorEntry();
        author.id = new SteamWorkshop.SteamID64(steamId);
        author.keys = List.of(key);

        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        ZBSVerifier.Verification result = ZBSVerifier.verify(jar, zbs, sha, null, Map.of(author.id, author));
        System.out.println("ZombieBuddy's verifier: " + result.getClass().getSimpleName()
            + (result.detailedMessage == null || result.detailedMessage.isBlank() ? "" : " (" + result.detailedMessage.trim() + ")"));
        if (!(result instanceof ZBSVerifier.ValidSignature)) {
            System.exit(1);
        }
    }
}
