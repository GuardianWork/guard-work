package com.guardwork.backend.company.search;

import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.common.PageResponse;
import com.guardwork.backend.common.utils.PaginationUtils;
import com.guardwork.backend.company.search.dto.CompanyDossierResponse;
import com.guardwork.backend.company.search.dto.CompanyDirectoryEntry;
import com.guardwork.backend.company.search.dto.CompanyRole;
import com.guardwork.backend.company.search.PublicCompanyService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Anonymous candidate company discovery and public company dossiers. */
@RestController
@RequestMapping("/api/companies/public")
public class PublicCompanyController {

  private final PublicCompanyService service;

  public PublicCompanyController(PublicCompanyService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<PageResponse<CompanyDirectoryEntry>> search(
      @RequestParam(required = false) String keyword,
      @PageableDefault(size = PaginationUtils.MAX_PAGE_SIZE) Pageable pageable,
      HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    return ApiResponse.ok(service.search(keyword, pageable));
  }

  @GetMapping("/{companyId}")
  public ApiResponse<CompanyDossierResponse> dossier(
      @PathVariable Long companyId, HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    return ApiResponse.ok(service.dossier(companyId));
  }

  @GetMapping("/{companyId}/roles")
  public ApiResponse<PageResponse<CompanyRole>> roles(
      @PathVariable Long companyId,
      @PageableDefault(size = PaginationUtils.MAX_PAGE_SIZE) Pageable pageable,
      HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    return ApiResponse.ok(service.roles(companyId, pageable));
  }
}
