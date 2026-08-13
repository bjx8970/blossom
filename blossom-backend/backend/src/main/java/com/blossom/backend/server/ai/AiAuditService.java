package com.blossom.backend.server.ai;

import com.blossom.common.base.exception.XzAbstractException;
import com.blossom.common.base.util.ServletUtil;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;

@Service
@AllArgsConstructor
public class AiAuditService {

    private final AiMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void success(Long userId, String action, Long resourceId,
                        Long beforeRevision, Long afterRevision, HttpServletRequest request) {
        success(userId, action, resourceId, beforeRevision, afterRevision, null, request);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void success(Long userId, String action, Long resourceId, Long beforeRevision,
                        Long afterRevision, String clientRequestId, HttpServletRequest request) {
        insert(userId, action, resourceId, beforeRevision, afterRevision,
                "SUCCESS", "", clientRequestId, request);
    }

    /** 写操作成功审计加入正文事务；审计失败时正文、引用、历史和幂等记录一并回滚。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void successInCurrentTransaction(Long userId, String action, Long resourceId,
                                            Long beforeRevision, Long afterRevision,
                                            String clientRequestId) {
        insert(userId, action, resourceId, beforeRevision, afterRevision,
                "SUCCESS", "", clientRequestId, ServletUtil.getRequest());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void failure(Long userId, String action, Long resourceId, Long beforeRevision,
                        RuntimeException error, HttpServletRequest request) {
        failure(userId, action, resourceId, beforeRevision, null, error, request);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void failure(Long userId, String action, Long resourceId, Long beforeRevision,
                        String clientRequestId, RuntimeException error, HttpServletRequest request) {
        String code = error instanceof XzAbstractException
                ? ((XzAbstractException) error).getCode() : error.getClass().getSimpleName();
        insert(userId, action, resourceId, beforeRevision, null,
                "FAILED", code, clientRequestId, request);
    }

    private void insert(Long userId, String action, Long resourceId, Long beforeRevision,
                        Long afterRevision, String result, String errorCode,
                        String clientRequestId, HttpServletRequest request) {
        AiEntities.Audit audit = new AiEntities.Audit();
        audit.setUserId(userId);
        audit.setTokenId(AiAuthContext.tokenId());
        audit.setAction(action);
        audit.setResourceType(action.startsWith("FOLDER") ? "FOLDER" : "ARTICLE");
        audit.setResourceId(resourceId);
        audit.setRequestId(normalizeRequestId(clientRequestId));
        audit.setBeforeRevision(beforeRevision);
        audit.setAfterRevision(afterRevision);
        audit.setResult(result);
        audit.setErrorCode(errorCode == null ? "" : truncate(errorCode, 40));
        String ip = ServletUtil.getIP(request);
        audit.setIp(ip == null ? "" : truncate(ip.trim(), 51));
        mapper.insertAudit(audit);
    }

    private String truncate(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }

    private String normalizeRequestId(String clientRequestId) {
        if (clientRequestId != null && clientRequestId.length() <= 80
                && clientRequestId.matches("[A-Za-z0-9._:-]+")) {
            return clientRequestId;
        }
        return AiAuthContext.requestId();
    }
}
