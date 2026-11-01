package com.guardwork.backend.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.guardwork.backend.auth.service.JwtService;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Uses an actual servlet container: MockMvc bypasses multipart parser limits. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.servlet.multipart.max-file-size=10MB", "spring.servlet.multipart.max-request-size=11MB"})
class ReportUploadHttpTest {
  @Value("${local.server.port}") int port;
  @Autowired JdbcTemplate db;
  @Autowired JwtService jwt;

  @Test void threeValidFiveMiBAttachmentsFitReportUploadWithoutWideningCompanyRequests() throws Exception {
    String name = "up_" + UUID.randomUUID().toString().replace("-", "");
    long actor = db.queryForObject("INSERT INTO users(first_name,last_name,username,email,password,role,status) "
        + "VALUES ('An','Nguyen',?,?,'unused','USER','ACTIVE') RETURNING id", Long.class, name, name + "@test.invalid");
    long company = db.queryForObject("INSERT INTO companies(name,tax_code,registration_certificate_url,verification_status) "
        + "VALUES ('Upload target',?,'retained','VERIFIED') RETURNING id", Long.class, UUID.randomUUID().toString());
    String token = "Bearer " + jwt.generateAccessToken(actor, name, name + "@test.invalid", "USER");
    String boundary = "gw99-" + UUID.randomUUID();
    var body = new ByteArrayOutputStream();
    write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"report\"\r\nContent-Type: application/json\r\n\r\n"
        + "{\"targetType\":\"COMPANY\",\"targetId\":\"" + company + "\",\"complaint\":\"Misleading company details\"}\r\n");
    for (int number = 0; number < 3; number++) {
      write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"proof" + number
          + ".pdf\"\r\nContent-Type: application/pdf\r\n\r\n");
      byte[] file = new byte[5 * 1024 * 1024];
      byte[] prefix = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
      System.arraycopy(prefix, 0, file, 0, prefix.length);
      file[file.length - 1] = (byte) number;
      body.write(file);
      write(body, "\r\n");
    }
    write(body, "--" + boundary + "--\r\n");
    byte[] payload = body.toByteArray();
    try (var client = HttpClient.newHttpClient()) {
      var response = client.send(request("/api/reports", boundary, token, payload), HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }
    // Send only declared-length headers: Tomcat may close a rejected request while a
    // high-level client is still sending its body, obscuring the HTTP 413 with broken pipe.
    try (var socket = new java.net.Socket("localhost", port)) {
      socket.setSoTimeout(10000);
      String headers = "POST /api/companies/verification-request HTTP/1.1\r\nHost: localhost:" + port
          + "\r\nAuthorization: " + token + "\r\nContent-Type: multipart/form-data; boundary=" + boundary
          + "\r\nContent-Length: " + payload.length + "\r\nConnection: close\r\n\r\n";
      socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      var reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
      assertThat(reader.readLine()).contains("413");
    }
  }

  private HttpRequest request(String path, String boundary, String token, byte[] bytes) {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30))
        .expectContinue(true)
        .header("Authorization", token).header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
  }

  private void write(ByteArrayOutputStream body, String value) throws Exception {
    body.write(value.getBytes(StandardCharsets.UTF_8));
  }
}
