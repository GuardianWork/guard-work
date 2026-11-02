package com.guardwork.backend.report.service;

import com.guardwork.backend.onboarding.service.OnboardingSecrets;
import com.guardwork.backend.report.model.ReportEvidence;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Scoped format/size validation; file signatures are not a malware scan. */
final class ReportEvidenceFiles {
  static final int MAX_BYTES = 5 * 1024 * 1024;
  private ReportEvidenceFiles() {}

  static List<ReportEvidence> validate(List<MultipartFile> files) {
    if (files == null) return List.of();
    if (files.size() > 3) throw tooLarge();
    List<ReportEvidence> result = new ArrayList<>();
    Set<String> hashes = new HashSet<>();
    for (MultipartFile file : files) {
      if (file == null || file.isEmpty()) throw UserReportService.invalid();
      if (file.getSize() > MAX_BYTES) throw tooLarge();
      byte[] bytes;
      try { bytes = file.getBytes(); }
      catch (IOException failure) { throw UserReportService.invalid(); }
      String type = mediaType(bytes);
      if (type == null || !type.equals(file.getContentType())) throw UserReportService.invalid();
      String hash = OnboardingSecrets.hash(bytes);
      if (!hashes.add(hash)) throw UserReportService.invalid();
      ReportEvidence evidence = new ReportEvidence();
      evidence.setId(UUID.randomUUID());
      evidence.setFilename(filename(file.getOriginalFilename()));
      evidence.setContentType(type);
      evidence.setSizeBytes(bytes.length);
      evidence.setContentHash(hash);
      evidence.setContent(bytes);
      result.add(evidence);
    }
    return result;
  }

  private static String mediaType(byte[] bytes) {
    if (startsWith(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII))) return "application/pdf";
    if (startsWith(bytes, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10})) return "image/png";
    if (startsWith(bytes, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})) return "image/jpeg";
    return null;
  }

  private static boolean startsWith(byte[] bytes, byte[] prefix) {
    return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
  }

  private static String filename(String original) {
    String name = original == null ? "" : original.replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\\p{Cf}]", "_").strip();
    if (name.isBlank() || name.equals(".") || name.equals("..")) return "evidence";
    if (name.codePointCount(0, name.length()) > 255) name = name.substring(0, name.offsetByCodePoints(0, 255));
    return name;
  }

  static ResponseStatusException tooLarge() {
    return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "REPORT_CONTENT_TOO_LARGE");
  }
}
