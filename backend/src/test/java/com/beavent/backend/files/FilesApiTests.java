package com.beavent.backend.files;

import static com.beavent.db.platform.Tables.AUDIT_LOG;
import static com.beavent.db.ref.Tables.CATEGORIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.beavent.backend.ApiTestSupport;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Files and media (plan step 0.9): uploads to real S3-compatible storage, processing, signed access. */
class FilesApiTests extends ApiTestSupport {

    static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
    final HttpClient http = HttpClient.newHttpClient();

    // --- helpers ---------------------------------------------------------------------------------------------

    UUID newOrg(Person owner) throws Exception {
        UUID category = system.select(CATEGORIES.ID)
                .from(CATEGORIES)
                .where(CATEGORIES.KIND.eq("BUSINESS"), CATEGORIES.CODE.eq("SPA"))
                .fetchSingle(CATEGORIES.ID);
        Call created = call(
                post("/v1/orgs"),
                Map.of(
                        "displayName",
                        "Zeta Spa",
                        "kind",
                        "BUSINESS",
                        "primaryCategoryId",
                        category,
                        "defaultLanguage",
                        "am"),
                owner.token());
        return UUID.fromString(created.text("id"));
    }

    /** Starts an upload, sends the bytes to storage, confirms it; returns the confirmed file. */
    Call upload(String token, String base, String purpose, String contentType, byte[] content, long declaredSize)
            throws Exception {
        Call started = call(
                post(base),
                Map.of(
                        "purpose",
                        purpose,
                        "contentType",
                        contentType,
                        "sizeBytes",
                        declaredSize,
                        "fileName",
                        "a/b\\c.bin"),
                token);
        assertThat(started.status()).as(String.valueOf(started.body())).isEqualTo(201);
        JsonNode target = started.body().get("upload");
        HttpRequest.Builder put = HttpRequest.newBuilder(
                        URI.create(target.get("url").asString()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content));
        target.get("headers")
                .properties()
                .forEach(h -> put.header(h.getKey(), h.getValue().asString()));
        assertThat(http.send(put.build(), HttpResponse.BodyHandlers.discarding())
                        .statusCode())
                .isEqualTo(200);
        String id = started.body().get("file").get("id").asString();
        return call(post(base + "/" + id + "/complete"), null, token);
    }

    Call upload(String token, String base, String purpose, String contentType, byte[] content) throws Exception {
        Call completed = upload(token, base, purpose, contentType, content, content.length);
        assertThat(completed.status()).as(String.valueOf(completed.body())).isEqualTo(200);
        return completed;
    }

    /** Waits until processing (scan, variants) has finished. */
    JsonNode processed(String token, String fileUrl) throws Exception {
        Instant deadline = Instant.now().plusSeconds(30);
        while (true) {
            JsonNode file = call(get(fileUrl), null, token).body();
            if (!List.of("PROCESSING", "PENDING_UPLOAD")
                            .contains(file.get("status").asString())
                    || Instant.now().isAfter(deadline)) {
                return file;
            }
            Thread.sleep(200);
        }
    }

    HttpResponse<byte[]> fetch(String url) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    /** A JPEG with a comment segment standing in for EXIF/GPS metadata. */
    static byte[] jpegWithMetadata(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(180, 40, 90));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", jpeg);
        byte[] plain = jpeg.toByteArray();
        byte[] comment = "GPS 9.0108N 38.7613E secret-location".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream withComment = new ByteArrayOutputStream();
        withComment.write(plain, 0, 2); // SOI
        withComment.write(new byte[] {(byte) 0xFF, (byte) 0xFE, 0, (byte) (comment.length + 2)});
        withComment.write(comment);
        withComment.write(plain, 2, plain.length - 2);
        return withComment.toByteArray();
    }

    static byte[] png(int width, int height) throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", png);
        return png.toByteArray();
    }

    static byte[] pdf(String text) {
        return ("%PDF-1.4\n% " + text + "\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    // --- tests -----------------------------------------------------------------------------------------------

    @Test
    void logoIsScannedCleanedAndPublishedWithVariantsBeforeItCanBeUsed() throws Exception {
        Person owner = newPerson();
        UUID orgId = newOrg(owner);
        String base = "/v1/orgs/" + orgId + "/files";
        byte[] logo = jpegWithMetadata(1200, 800);

        Call completed = upload(owner.token(), base, "LOGO", "image/jpeg", logo);
        String fileUrl = base + "/" + completed.text("id");
        assertThat(completed.text("fileName")).isEqualTo("c.bin");
        assertThat(completed.body().has("url"))
                .as("nothing public before processing")
                .isFalse();

        JsonNode file = processed(owner.token(), fileUrl);
        assertThat(file.get("status").asString()).isEqualTo("READY");
        assertThat(file.get("width").asInt()).isEqualTo(1200);
        assertThat(file.get("variants").propertyNames()).containsExactlyInAnyOrder("w320", "w960");
        HttpResponse<byte[]> published = fetch(file.get("url").asString());
        assertThat(published.statusCode()).isEqualTo(200);
        assertThat(new String(published.body(), StandardCharsets.ISO_8859_1))
                .as("metadata stripped by re-encoding")
                .doesNotContain("secret-location");
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(
                                fetch(file.get("variants").get("w320").asString())
                                        .body()))
                        .getWidth())
                .isEqualTo(320);

        Call org = call(get("/v1/orgs/" + orgId), null, owner.token());
        Call withLogo = call(
                patch("/v1/orgs/" + orgId).header("If-Match", org.etag()),
                Map.of("logoFileId", completed.text("id")),
                owner.token());
        assertThat(withLogo.status()).isEqualTo(200);
        assertThat(withLogo.text("logoFileId")).isEqualTo(completed.text("id"));

        // Wrong type for the purpose, and a size that does not match the declaration, are refused.
        assertThat(call(
                                post(base),
                                Map.of("purpose", "LOGO", "contentType", "application/pdf", "sizeBytes", 10),
                                owner.token())
                        .status())
                .isEqualTo(422);
        assertThat(upload(owner.token(), base, "GALLERY", "image/png", png(10, 10), 99_999)
                        .status())
                .isEqualTo(422);
    }

    @Test
    void kybDocumentsAreReadableOnlyByAuthorisedMembersThroughShortLivedAuditedUrls() throws Exception {
        Person owner = newPerson();
        UUID orgId = newOrg(owner);
        String base = "/v1/orgs/" + orgId + "/files";
        byte[] license = pdf("trade license 123");
        String fileId =
                upload(owner.token(), base, "KYC", "application/pdf", license).text("id");
        JsonNode file = processed(owner.token(), base + "/" + fileId);
        assertThat(file.get("status").asString()).isEqualTo("READY");
        assertThat(file.get("visibility").asString()).isEqualTo("SENSITIVE");
        assertThat(file.has("url")).isFalse();

        Call entity = call(
                post("/v1/orgs/" + orgId + "/legal-entities"),
                Map.of("countryCode", "ET", "entityType", "PLC", "registeredName", "Zeta PLC", "taxRegime", "TOT"),
                owner.token());
        Call kyb = call(
                post("/v1/orgs/" + orgId + "/verification-cases"),
                Map.of(
                        "subject", "ORGANIZATION_KYB",
                        "legalEntityId", entity.text("id"),
                        "documents", List.of(Map.of("docType", "TRADE_LICENSE", "fileId", fileId))),
                owner.token());
        assertThat(kyb.status()).isEqualTo(201);

        Call access = call(post(base + "/" + fileId + "/access-url"), null, owner.token());
        assertThat(access.status()).isEqualTo(200);
        HttpResponse<byte[]> download = fetch(access.text("url"));
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(license);
        assertThat(system.fetchExists(
                        AUDIT_LOG, AUDIT_LOG.ACTION.eq("file.accessed").and(AUDIT_LOG.TARGET_ID.eq(fileId))))
                .isTrue();

        // Without the signature the object is not readable; other organizations do not see it at all.
        String unsigned = access.text("url").substring(0, access.text("url").indexOf('?'));
        assertThat(fetch(unsigned).statusCode()).isEqualTo(403);
        Person outsider = newPerson();
        UUID otherOrg = newOrg(outsider);
        assertThat(call(post("/v1/orgs/" + otherOrg + "/files/" + fileId + "/access-url"), null, outsider.token())
                        .status())
                .isEqualTo(404);
        assertThat(call(get("/v1/me/files/" + fileId), null, owner.token()).status())
                .as("an organization's file is not a personal file")
                .isEqualTo(404);

        // A member without the permission cannot obtain a URL.
        String phone = newPhone();
        String receptionistRole = null;
        for (JsonNode role : call(get("/v1/orgs/" + orgId + "/roles"), null, owner.token())
                .body()
                .get("items")) {
            if (role.get("code").asString().equals("RECEPTIONIST")) {
                receptionistRole = role.get("id").asString();
            }
        }
        call(
                post("/v1/orgs/" + orgId + "/invitations"),
                Map.of("phone", phone, "roleId", receptionistRole),
                owner.token());
        String token = sms.lastMessageTo(phone).replaceAll("(?s).*token=([A-Za-z0-9_-]+).*", "$1");
        String receptionist = signInWithOtp(phone).text("accessToken");
        assertThat(call(post("/v1/invitations/accept"), Map.of("token", token), receptionist)
                        .status())
                .isEqualTo(200);
        assertThat(call(post(base + "/" + fileId + "/access-url"), null, receptionist)
                        .status())
                .isEqualTo(403);
    }

    @Test
    void infectedOrDisguisedUploadsAreRejectedAndDeleted() throws Exception {
        Person owner = newPerson();
        UUID orgId = newOrg(owner);
        String base = "/v1/orgs/" + orgId + "/files";

        String infected = upload(owner.token(), base, "KYC", "application/pdf", pdf(EICAR))
                .text("id");
        assertThat(processed(owner.token(), base + "/" + infected).get("status").asString())
                .isEqualTo("REJECTED");
        String disguised = upload(owner.token(), base, "COVER", "image/png", pdf("not an image"))
                .text("id");
        assertThat(processed(owner.token(), base + "/" + disguised)
                        .get("status")
                        .asString())
                .isEqualTo("REJECTED");
        assertThat(system.fetchExists(
                        AUDIT_LOG, AUDIT_LOG.ACTION.eq("file.rejected").and(AUDIT_LOG.TARGET_ID.eq(infected))))
                .isTrue();
        assertThat(call(post(base + "/" + infected + "/access-url"), null, owner.token())
                        .status())
                .isEqualTo(409);

        Call org = call(get("/v1/orgs/" + orgId), null, owner.token());
        assertThat(call(
                                patch("/v1/orgs/" + orgId).header("If-Match", org.etag()),
                                Map.of("coverFileId", disguised),
                                owner.token())
                        .status())
                .isEqualTo(422);
    }

    @Test
    void avatarsMustBeTheirOwnersProcessedImage() throws Exception {
        Person selam = newPerson();
        String id = upload(selam.token(), "/v1/me/files", "AVATAR", "image/png", png(400, 400))
                .text("id");
        JsonNode avatar = processed(selam.token(), "/v1/me/files/" + id);
        assertThat(avatar.get("status").asString()).isEqualTo("READY");
        assertThat(avatar.get("url").asString()).contains("/users/");
        Call me = call(patch("/v1/me"), Map.of("avatarFileId", id), selam.token());
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.text("avatarFileId")).isEqualTo(id);

        Person other = newPerson();
        assertThat(call(patch("/v1/me"), Map.of("avatarFileId", id), other.token())
                        .status())
                .isEqualTo(422);
        assertThat(call(get("/v1/me/files/" + id), null, other.token()).status())
                .isEqualTo(404);
        assertThat(call(
                                post("/v1/me/files"),
                                Map.of("purpose", "KYC", "contentType", "application/pdf", "sizeBytes", 10),
                                selam.token())
                        .status())
                .isEqualTo(422);
    }
}
