package com.guardwork.backend.job.controller;

import com.guardwork.backend.common.ApiResponse;
import com.guardwork.backend.common.PageResponse;
import com.guardwork.backend.common.utils.PaginationUtils;
import com.guardwork.backend.job.dto.JobSearchRequest;
import com.guardwork.backend.job.search.JobSearchService;
import com.guardwork.backend.job.service.JobPublicationService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JobSearchController {

  private final JobSearchService service;

  public JobSearchController(JobSearchService service) {
    this.service = service;
  }

  @GetMapping("/api/jobs/search")
  public ApiResponse<PageResponse<JobPublicationService.PublicDetail>> search(
      @ModelAttribute JobSearchRequest request,
      @PageableDefault(size = PaginationUtils.DEFAULT_PAGE_SIZE) Pageable pageable,
      HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    return ApiResponse.ok(service.search(request, pageable));
  }
}
