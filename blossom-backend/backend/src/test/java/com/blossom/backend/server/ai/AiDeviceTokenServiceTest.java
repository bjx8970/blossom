package com.blossom.backend.server.ai;

import com.blossom.backend.base.param.ParamEnum;
import com.blossom.backend.base.param.ParamService;
import com.blossom.backend.base.param.pojo.ParamEntity;
import com.blossom.common.base.exception.XzException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiDeviceTokenServiceTest {

    @Mock
    private AiMapper mapper;
    @Mock
    private ParamService paramService;

    private AiDeviceTokenService service;

    @BeforeEach
    void setUp() {
        ParamEntity pepper = new ParamEntity();
        pepper.setParamValue("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        // 部分拒绝路径在计算 HMAC 前返回，避免这些测试因共享准备数据产生无关的严格桩失败。
        lenient().when(paramService.getValue(ParamEnum.AI_TOKEN_PEPPER)).thenReturn(pepper);
        service = new AiDeviceTokenService(mapper, paramService);
    }

    @Test
    void issuesOnlyHashedTokenAndEnforcesReadScopeExpiryAndRevocation() {
        AiModels.TokenIssueReq request = new AiModels.TokenIssueReq();
        request.setDeviceId("desktop_device_01");
        request.setDeviceName("Blossom Desktop Test");
        request.setScopes(Collections.singletonList(AiDeviceTokenService.READ_SCOPE));

        when(mapper.selectDeviceTokenByDevice(request.getDeviceId(), 7L)).thenReturn(null);
        when(mapper.insertDeviceToken(any(AiEntities.DeviceToken.class))).thenAnswer(invocation -> {
            AiEntities.DeviceToken record = invocation.getArgument(0);
            record.setId(42L);
            return 1;
        });

        AiModels.TokenRes response = service.issue(7L, request);
        assertNotNull(response.getToken());

        ArgumentCaptor<AiEntities.DeviceToken> captor = ArgumentCaptor.forClass(AiEntities.DeviceToken.class);
        verify(mapper).insertDeviceToken(captor.capture());
        AiEntities.DeviceToken stored = captor.getValue();
        assertNotNull(stored.getTokenHash());
        assertNotEquals(response.getToken(), stored.getTokenHash());

        AiEntities.TokenPrincipal principal = principal(stored);
        when(mapper.selectPrincipalByDevice(request.getDeviceId(), 7L)).thenReturn(principal);
        assertEquals(7L, service.authenticate(response.getToken(), false).getUserId());

        XzException denied = assertThrows(XzException.class, () -> service.authenticate(response.getToken(), true));
        assertEquals("AUTH-40302", denied.getCode());

        principal.setExpireTime(new Date(System.currentTimeMillis() - 1_000L));
        XzException expired = assertThrows(XzException.class, () -> service.authenticate(response.getToken(), false));
        assertEquals("AUTH-40101", expired.getCode());

        principal.setExpireTime(new Date(System.currentTimeMillis() + 60_000L));
        principal.setRevokedTime(new Date());
        XzException revoked = assertThrows(XzException.class, () -> service.authenticate(response.getToken(), false));
        assertEquals("AUTH-40101", revoked.getCode());
    }

    @Test
    void neverRotatesAnExplicitlyRevokedCredential() {
        AiEntities.DeviceToken revoked = new AiEntities.DeviceToken();
        revoked.setId(42L);
        revoked.setUserId(7L);
        revoked.setRevokedTime(new Date());
        when(mapper.selectDeviceToken(42L, 7L)).thenReturn(revoked);

        XzException error = assertThrows(XzException.class,
                () -> service.rotate(7L, 42L, new AiModels.TokenRotateReq()));

        assertEquals("AUTH-40101", error.getCode());
    }

    private AiEntities.TokenPrincipal principal(AiEntities.DeviceToken stored) {
        AiEntities.TokenPrincipal principal = new AiEntities.TokenPrincipal();
        principal.setId(stored.getId());
        principal.setUserId(stored.getUserId());
        principal.setDeviceId(stored.getDeviceId());
        principal.setTokenHash(stored.getTokenHash());
        principal.setScopes(stored.getScopes());
        principal.setExpireTime(stored.getExpireTime());
        principal.setRevokedTime(stored.getRevokedTime());
        principal.setUsername("tester");
        principal.setUserType(1);
        principal.setUserDelTime(0L);
        return principal;
    }
}
