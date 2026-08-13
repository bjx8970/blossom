package com.blossom.backend.server.ai;

import com.blossom.backend.base.auth.AuthContext;
import com.blossom.common.base.pojo.R;
import lombok.AllArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 设备令牌管理只能使用现有登录会话，设备令牌自身不能访问此路径。 */
@RestController
@AllArgsConstructor
@RequestMapping("/api/ai/manage/v1/device-tokens")
public class AiDeviceTokenController {

    private final AiDeviceTokenService service;

    @GetMapping
    public R<List<AiModels.TokenRes>> list() {
        return R.ok(service.list(AuthContext.getUserId()));
    }

    @PostMapping
    public R<AiModels.TokenRes> issue(@Validated @RequestBody AiModels.TokenIssueReq req) {
        return R.ok(service.issue(AuthContext.getUserId(), req));
    }

    @PostMapping("/{id}/rotate")
    public R<AiModels.TokenRes> rotate(@PathVariable("id") Long id,
                                       @Validated @RequestBody(required = false) AiModels.TokenRotateReq req) {
        return R.ok(service.rotate(AuthContext.getUserId(), id, req));
    }

    @PostMapping("/{id}/revoke")
    public R<?> revoke(@PathVariable("id") Long id) {
        service.revoke(AuthContext.getUserId(), id);
        return R.ok();
    }
}
