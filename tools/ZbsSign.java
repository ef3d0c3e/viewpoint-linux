// SPDX-FileCopyrightText: 2026 ef3d0c3e
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Writes a ZombieBuddy .zbs signature for a jar with an ssh-ed25519 key held by ssh-agent.
 * The private key is never read: the agent signs. For ed25519 the agent returns a plain
 * Ed25519 signature over exactly the bytes sent, which is what ZombieBuddy verifies.
 *
 *   java ZbsSign.java <jar> <steamid64> <key.pub> <agent socket>
 *
 * ZombieBuddy (ZBSVerifier) expects, next to the jar, <jar>.zbs containing:
 *   ZBS
 *   SteamID64:<17 digits>
 *   Signature:<128 hex>   Ed25519 over "ZBS:<steamid64>:<lowercase sha256 hex of the jar>"
 * and the 32-byte public key as "JavaModZBS:<64 hex>" in the Steam profile summary.
 */
public final class ZbsSign {
    private static final int SSH_AGENT_FAILURE = 5;
    private static final int SSH_AGENTC_SIGN_REQUEST = 13;
    private static final int SSH_AGENT_SIGN_RESPONSE = 14;
    /** X.509 SubjectPublicKeyInfo prefix for a raw 32-byte Ed25519 key. */
    private static final byte[] ED25519_SPKI = HexFormat.of().parseHex("302a300506032b6570032100");

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("usage: ZbsSign <jar> <steamid64> <key.pub> <agent socket>");
        }
        Path jar = Path.of(args[0]);
        String steamId = args[1];
        if (!steamId.matches("\\d{17}")) {
            throw new IllegalArgumentException("SteamID64 must be 17 digits: " + steamId);
        }

        String[] pubLine = Files.readString(Path.of(args[2])).trim().split("\\s+");
        if (!pubLine[0].equals("ssh-ed25519")) {
            throw new IllegalArgumentException(args[2] + " is " + pubLine[0] + ", ZombieBuddy needs ssh-ed25519");
        }
        byte[] keyBlob = Base64.getDecoder().decode(pubLine[1]);
        byte[] rawPublic = Arrays.copyOfRange(keyBlob, keyBlob.length - 32, keyBlob.length);

        String jarSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        byte[] message = ("ZBS:" + steamId + ":" + jarSha).getBytes(StandardCharsets.UTF_8);

        byte[] signature = agentSign(Path.of(args[3]), keyBlob, message);

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey(rawPublic));
        verifier.update(message);
        if (!verifier.verify(signature)) {
            throw new IllegalStateException("the agent's signature does not verify against " + args[2]);
        }

        Path zbs = jar.resolveSibling(jar.getFileName() + ".zbs");
        Files.writeString(zbs, "ZBS\nSteamID64:" + steamId + "\nSignature:" + HexFormat.of().formatHex(signature) + "\n");
        System.out.println("signed " + jar.getFileName() + " (sha256 " + jarSha + ")");
        System.out.println("wrote  " + zbs);
        System.out.println("Steam profile summary needs: JavaModZBS:" + HexFormat.of().formatHex(rawPublic));
    }

    private static PublicKey publicKey(byte[] raw) throws Exception {
        byte[] spki = Arrays.copyOf(ED25519_SPKI, ED25519_SPKI.length + raw.length);
        System.arraycopy(raw, 0, spki, ED25519_SPKI.length, raw.length);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
    }

    private static byte[] agentSign(Path socket, byte[] keyBlob, byte[] data) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(body);
        out.writeByte(SSH_AGENTC_SIGN_REQUEST);
        writeString(out, keyBlob);
        writeString(out, data);
        out.writeInt(0);

        try (SocketChannel agent = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            agent.connect(UnixDomainSocketAddress.of(socket));
            ByteBuffer request = ByteBuffer.allocate(4 + body.size()).putInt(body.size()).put(body.toByteArray()).flip();
            while (request.hasRemaining()) {
                agent.write(request);
            }
            ByteBuffer reply = ByteBuffer.wrap(readFully(agent, ByteBuffer.wrap(readFully(agent, ByteBuffer.allocate(4)).array()).getInt()).array());
            int type = reply.get() & 0xff;
            if (type == SSH_AGENT_FAILURE) {
                throw new IOException("ssh-agent refused to sign: is the key loaded (ssh-add -l)?");
            }
            if (type != SSH_AGENT_SIGN_RESPONSE) {
                throw new IOException("unexpected ssh-agent reply type " + type);
            }
            ByteBuffer sig = ByteBuffer.wrap(readString(reply));
            String algorithm = new String(readString(sig), StandardCharsets.US_ASCII);
            byte[] raw = readString(sig);
            if (!algorithm.equals("ssh-ed25519") || raw.length != 64) {
                throw new IOException("expected a 64-byte ssh-ed25519 signature, got " + algorithm + " of " + raw.length + " bytes");
            }
            return raw;
        }
    }

    private static ByteBuffer readFully(SocketChannel channel, int length) throws IOException {
        return readFully(channel, ByteBuffer.allocate(length));
    }

    private static ByteBuffer readFully(SocketChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IOException("ssh-agent closed the connection");
            }
        }
        return buffer.flip();
    }

    private static void writeString(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] readString(ByteBuffer in) {
        byte[] bytes = new byte[in.getInt()];
        in.get(bytes);
        return bytes;
    }
}
