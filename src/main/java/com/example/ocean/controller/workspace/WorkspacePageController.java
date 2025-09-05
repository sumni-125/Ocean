package com.example.ocean.controller.workspace;

import com.example.ocean.domain.Workspace;
import com.example.ocean.domain.WorkspaceDept;
import com.example.ocean.domain.WorkspaceMember;
import com.example.ocean.service.WorkspaceService;
import com.example.ocean.security.oauth.UserPrincipal;
import com.example.ocean.util.S3Uploader;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 페이지 렌더링 전용 컨트롤러 (HTML 응답)
@Slf4j
@Controller
@RequiredArgsConstructor
public class WorkspacePageController {

    private final WorkspaceService workspaceService;

    @Value("${app.s3.profile-dir}")
    private String uploadDir;
    private  S3Uploader s3Uploader;

    @GetMapping("/workspace")
    public String workspaceListPage(@AuthenticationPrincipal UserPrincipal userPrincipal,
                                    HttpServletRequest request,
                                    Authentication authentication,
                                    Model model) {

        // 임시 해결책: 쿠키에서 tempAccessToken 확인
        if (userPrincipal == null) {
            Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if ("tempAccessToken".equals(cookie.getName())) {
                        // 토큰으로 사용자 정보 조회 (임시)
                        log.info("임시 토큰으로 사용자 조회 시도");
                        // TODO: JWT 토큰 파싱해서 userId 가져오기
                        break;
                    }
                }
            }
        }

        // 그래도 null이면 로그인 페이지로
        if (userPrincipal == null) {
            log.error("UserPrincipal is null - 로그인 페이지로 리다이렉트");
            return "redirect:/login";
        }

        try {
            log.info("=== Workspace 페이지 접근 ===");
            log.info("UserPrincipal ID: {}", userPrincipal.getId());
            log.info("Authentication Name: {}", userPrincipal.getName());

            String userId = userPrincipal.getId();
            log.info("사용자 ID로 워크스페이스 조회 시작: {}", userId);

            List<Workspace> workspaces = workspaceService.getWorkspacesByUserId(userId);
            log.info("조회된 워크스페이스 개수: {}", workspaces != null ? workspaces.size() : 0);


            model.addAttribute("workspaceList", workspaces != null ? workspaces : new ArrayList<>());
            //model.addAttribute("workspaceList", workspaces);

            return "workspace/workspace";

        } catch (Exception e) {
            log.error("워크스페이스 조회 중 오류", e);
            e.printStackTrace();

            // 빈 리스트로라도 페이지는 표시
            model.addAttribute("workspaceList", new ArrayList<>());
            model.addAttribute("error", "워크스페이스를 불러올 수 없습니다.");

            return "workspace/workspace";
        }
    }

    // create-workspace 페이지 보여주기
    @GetMapping("/workspace/create")
    public String createWorkspacePage(@AuthenticationPrincipal UserPrincipal userPrincipal,
                                      Model model) {
        // 인증 확인
        if (userPrincipal == null) {
            log.error("인증되지 않은 사용자 - 로그인 페이지로 리다이렉트");
            return "redirect:/login";
        }

        log.info("워크스페이스 생성 페이지 접근 - 사용자: {}", userPrincipal.getName());

        // 필요한 경우 모델에 데이터 추가
        model.addAttribute("userId", userPrincipal.getId());
        model.addAttribute("userName", userPrincipal.getName());

        return "workspace/create-workspace";
    }

    /**
     * 워크스페이스 참가 페이지
     *
     * @param userPrincipal 인증된 사용자 정보
     * @param model 뷰에 전달할 모델
     * @return join-workspace 페이지
     */
    @GetMapping("/invitations/join")
    public String joinWorkspacePage(@AuthenticationPrincipal UserPrincipal userPrincipal,
                                    Model model) {
        // 인증 확인
        if (userPrincipal == null) {
            log.error("인증되지 않은 사용자 - 로그인 페이지로 리다이렉트");
            return "redirect:/login";
        }

        log.info("워크스페이스 참가 페이지 접근 - 사용자: {}", userPrincipal.getName());

        // 필요한 경우 모델에 데이터 추가
        model.addAttribute("userId", userPrincipal.getId());
        model.addAttribute("userName", userPrincipal.getName());

        return "workspace/join-workspace";
    }

    @GetMapping("/oauth2-redirect.html")
    public String oauth2RedirectPage() {
        return "oauth2-redirect";  // templates/oauth2-redirect.html
    }

    @PostMapping("/workspace/{workspaceCd}/set-profile")
    @ResponseBody
    public String handleSetProfile(
            @PathVariable String workspaceCd,
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @RequestParam("userNickname") String userNickname,
            @RequestParam("email") String email,
            @RequestParam(value = "phoneNum", required = false) String phoneNum,
            @RequestParam(value = "statusMsg", required = false) String statusMsg,
            @RequestParam("deptCd") String deptCd,
            @RequestParam("position") String position,
            @RequestParam(value = "userImg", required = false) MultipartFile userImgFile) {

        try {
            log.info("프로필 설정 시작 - workspaceCd: {}, userId: {}", workspaceCd, userPrincipal.getId());

            String userImgPath = null;  // ⭐ 전체 경로 저장
            if (userImgFile != null && !userImgFile.isEmpty()) {
                userImgPath = saveProfileImage(userImgFile);
            }

            // 멤버 존재 여부 확인
            WorkspaceMember existingMember = workspaceService.findMemberByWorkspaceAndUser(workspaceCd, userPrincipal.getId());

            // ✅ 최초 프로필 등록 여부 판단
            boolean isFirstProfile = existingMember != null && existingMember.getUserNickname() == null;

            if (existingMember == null) {
                // 새 멤버 추가 - userImgPath 포함
                workspaceService.insertUserProfileToWorkspace(
                        workspaceCd,
                        userPrincipal.getId(),
                        userNickname,
                        statusMsg,
                        email,
                        phoneNum,
                        "MEMBER",
                        userImgPath  // ⭐ 이미지 경로 추가
                );
            } else {
                // 기존 멤버 업데이트 - userImgPath 포함
                workspaceService.updateWorkspaceProfile(
                        workspaceCd,
                        userPrincipal.getId(),
                        userNickname,
                        statusMsg,
                        email,
                        phoneNum,
                        "MEMBER",
                        userImgPath != null ? userImgPath : existingMember.getUserImg() // ⭐ 기존 이미지 유지
                );
            }

            if (isFirstProfile) {
                workspaceService.insertNewMemberNotification(workspaceCd, userNickname);
            }

            // 부서 및 직급 정보 업데이트
            workspaceService.updateDeptAndPosition(
                    workspaceCd,
                    userPrincipal.getId(),
                    deptCd,
                    position
            );



            log.info("프로필 설정 완료 - workspaceCd: {}, userId: {}", workspaceCd, userPrincipal.getId());
            return "success";

        } catch (Exception e) {
            log.error("프로필 설정 중 오류 발생", e);
            return "error: " + e.getMessage();
        }
    }

    //
    @PostMapping("/workspace/{workspaceCd}/set-profile2")
    @ResponseBody
    public String handleSetProfile2(
            @PathVariable String workspaceCd,
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @RequestParam("userNickname") String userNickname,
            @RequestParam("email") String email,
            @RequestParam(value = "phoneNum", required = false) String phoneNum,
            @RequestParam(value = "statusMsg", required = false) String statusMsg,
            @RequestParam("deptCd") String deptCd,
            @RequestParam("position") String position,
            @RequestParam(value = "userImg", required = false) MultipartFile userImgFile) {

        try {
            log.info("프로필 설정 시작 - workspaceCd: {}, userId: {}", workspaceCd, userPrincipal.getId());
            log.info("deptcd===> {}", deptCd);
            String userImgPath = null;  // ⭐ 전체 경로 저장
            if (userImgFile != null && !userImgFile.isEmpty()) {
                userImgPath = saveProfileImage(userImgFile);
            }

            // 멤버 존재 여부 확인
            WorkspaceMember existingMember = workspaceService.findMemberByWorkspaceAndUser(workspaceCd, userPrincipal.getId());

            if (existingMember == null) {
                // 새 멤버 추가 - userImgPath 포함
                workspaceService.insertUserProfileToWorkspace(
                        workspaceCd,
                        userPrincipal.getId(),
                        userNickname,
                        statusMsg,
                        email,
                        phoneNum,
                        "MEMBER",
                        userImgPath  // ⭐ 이미지 경로 추가
                );
            } else {
                // 기존 멤버 업데이트 - userImgPath 포함
                workspaceService.updateWorkspaceProfile(
                        workspaceCd,
                        userPrincipal.getId(),
                        userNickname,
                        statusMsg,
                        email,
                        phoneNum,
                        "MEMBER",
                        userImgPath != null ? userImgPath : existingMember.getUserImg() // ⭐ 기존 이미지 유지
                );
            }



            // 부서 및 직급 정보 업데이트
            workspaceService.updateDeptAndPosition2(
                    workspaceCd,
                    userPrincipal.getId(),
                    position
            );



            log.info("프로필 설정 완료 - workspaceCd: {}, userId: {}", workspaceCd, userPrincipal.getId());
            return "success";

        } catch (Exception e) {
            log.error("프로필 설정 중 오류 발생", e);
            return "error: " + e.getMessage();
        }
    }

    // 프로필 이미지 저장 헬퍼 메소드 - 중복 제거!
    private String saveProfileImage(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return null;
        }
/*
        // ⭐ 하드코딩된 경로 대신 주입받은 uploadDir 사용
        File uploadDirectory = new File(uploadDir + "/profiles/");
        if (!uploadDirectory.exists()) {
            uploadDirectory.mkdirs();
        }

        String originalFileName = file.getOriginalFilename();
        String ext = originalFileName != null ? originalFileName.substring(originalFileName.lastIndexOf(".")) : "";
        String savedFileName = UUID.randomUUID().toString() + ext;

        File destinationFile = new File(uploadDirectory, savedFileName);
        file.transferTo(destinationFile);*/
        String savedFilePath = s3Uploader.upload(file, uploadDir);

        // ⭐ 웹 경로 반환
        return savedFilePath;
    }

    @GetMapping("/workspace/{workspaceCd}")
    public String workspaceDetailPage(@PathVariable String workspaceCd,
                                      @AuthenticationPrincipal UserPrincipal userPrincipal,
                                      Model model) {
        if (userPrincipal == null) {
            return "redirect:/login";
        }

        // 4. 워크스페이스 존재 여부 확인
        Workspace workspace = workspaceService.findWorkspaceByCd(workspaceCd);
        if (workspace == null) {
            model.addAttribute("error", "해당 워크스페이스를 찾을 수 없습니다.");
            return "workspace/error";
        }

        // 1. 멤버 정보 조회
        WorkspaceMember member = workspaceService.findMemberByWorkspaceAndUser(
                workspaceCd,
                userPrincipal.getId()
        );

        // 2. 멤버가 없는 경우
        if (member == null) {
            log.warn("워크스페이스 참가 페이지 접근 - 사용자: {}, 워크스페이스 코드: {}, member == null", userPrincipal.getName(), workspaceCd);
            return "redirect:/workspace/" + workspaceCd + "/set-profile";
        }

        // 3. 프로필 정보가 없는 경우
        else if (member.getEmail() == null || member.getEmail().isBlank()) {
            return "redirect:/workspace/" + workspaceCd + "/set-profile";
        }

        List<WorkspaceMember> members = workspaceService.getWorkspaceMembers(workspaceCd);

        LocalDate endDate = LocalDate.ofInstant(workspace.getEndDate().toInstant(), ZoneId.systemDefault());
        LocalDate createdDate = LocalDate.ofInstant(workspace.getCreatedDate().toInstant(), ZoneId.systemDefault());
        long dday = Math.max(ChronoUnit.DAYS.between(LocalDate.now(), endDate), 0);
        long totalDays = ChronoUnit.DAYS.between(createdDate, endDate);
        long passedDays = ChronoUnit.DAYS.between(createdDate, LocalDate.now());
        int progressPercent = totalDays > 0 ? (int) ((double) passedDays / totalDays * 100) : 100;

        Map<String, Object> eventSummary = workspaceService.getEventSummary(workspaceCd);

        model.addAttribute("workspace", workspace);
        model.addAttribute("members", members);
        model.addAttribute("dday", dday);
        model.addAttribute("progressPercent", progressPercent);
        model.addAttribute("eventSummary", eventSummary);

        // 5. 모든 조건을 만족하면 wsmain으로 리다이렉트
        return "redirect:/wsmain?workspaceCd=" + workspaceCd;
    }

    @GetMapping("/workspace/{workspaceCd}/set-profile")
    public String setProfilePageByPath(@PathVariable String workspaceCd,
                                       @AuthenticationPrincipal UserPrincipal userPrincipal,
                                       Model model) {
        List<WorkspaceDept> departments = workspaceService.getDepartments(workspaceCd);

        WorkspaceMember member = workspaceService.findMemberByWorkspaceAndUser(workspaceCd, userPrincipal.getId());

        // ✅ null 방어 추가
        if (member == null) {
            // 잘못된 접근이므로 워크스페이스 상세로 리다이렉트
            return "redirect:/workspace/" + workspaceCd;
        }

        model.addAttribute("workspaceCd", workspaceCd);
        model.addAttribute("departments", departments);
        model.addAttribute("member", member);

        return "workspace/set-profile";
    }

}
