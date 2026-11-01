package com.guardwork.backend.report.repository;

import com.guardwork.backend.report.model.ReportEvidence;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReportEvidenceRepository extends JpaRepository<ReportEvidence, UUID> {
  interface Metadata {
    UUID getId();
    String getFilename();
    String getContentType();
    long getSizeBytes();
    boolean getAvailable();
  }

  @Query("select e.id as id, e.filename as filename, e.contentType as contentType, e.sizeBytes as sizeBytes, "
      + "case when e.content is null then false else true end as available "
      + "from ReportEvidence e where e.reportId = :reportId order by e.id")
  List<Metadata> findMetadataByReportId(@Param("reportId") Long reportId);
  Optional<ReportEvidence> findByIdAndReportId(UUID id, Long reportId);
}
