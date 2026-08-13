<template>
  <div class="config-root ai-config-root" v-loading="loading">
    <div class="title">AI 工具接入</div>
    <div class="desc">
      在本机启动仅监听 <code>127.0.0.1</code> 的 MCP 服务，让受信任的 AI 工具读取和修改当前账号的笔记。服务默认关闭，启用前请确认风险。
    </div>

    <el-alert v-if="!desktopAvailable" title="AI 工具接入仅在 Blossom 桌面客户端中可用" type="warning" :closable="false" show-icon />
    <el-alert
      v-else-if="status.storageSecurity === 'basic_text'"
      title="当前系统无法使用安全凭证存储。为避免登录凭证以基础文本保存，AI 工具接入无法启用。"
      type="error"
      :closable="false"
      show-icon />
    <el-alert v-if="status.error" :title="status.error" type="error" :closable="false" show-icon class="status-alert" />

    <el-form label-position="right" label-width="130px" style="max-width: 820px">
      <el-form-item label="服务状态">
        <bl-row just="flex-start">
          <el-tag :type="status.running ? 'success' : status.state === 'error' ? 'danger' : 'info'">
            {{ stateText }}
          </el-tag>
          <el-button text bg class="refresh-btn" @click="reload(false)"> <span class="iconbl bl-refresh-line"></span>刷新 </el-button>
        </bl-row>
      </el-form-item>

      <el-form-item label="启用服务">
        <el-switch
          v-model="enabledInput"
          :loading="changingEnabled"
          :disabled="!desktopAvailable || !userStore.isLogin || status.storageSecurity !== 'secure'"
          @change="handleEnabled" />
        <div class="conf-tip">启用后，持有连接配置的本机程序可以按服务端授权读取或修改笔记。请勿把连接配置发送给他人。</div>
      </el-form-item>

      <el-form-item label="监听地址">
        <el-input :model-value="status.host || '127.0.0.1'" disabled />
        <div class="conf-tip">固定为本机回环地址，不接受局域网或公网连接。</div>
      </el-form-item>

      <el-form-item label="监听端口">
        <bl-row just="flex-start">
          <el-input-number v-model="portInput" :min="1024" :max="65535" controls-position="right" :disabled="!desktopAvailable" />
          <el-button class="port-save" :disabled="!desktopAvailable || portInput === status.port" @click="savePort">保存端口</el-button>
        </bl-row>
      </el-form-item>

      <el-form-item label="服务地址">
        <el-input :model-value="status.endpoint || '服务未启动'" readonly />
      </el-form-item>

      <el-form-item label="当前账号">
        <span>{{ status.authenticated ? `${status.username || '未知用户'}（${status.userId || '-'}）` : '尚未同步登录信息' }}</span>
      </el-form-item>

      <el-form-item label="权限">
        <bl-row v-if="status.scopes?.length" just="flex-start">
          <el-tag v-for="scope in status.scopes" :key="scope" type="info">{{ scope }}</el-tag>
        </bl-row>
        <span v-else>尚未授予权限</span>
      </el-form-item>

      <el-form-item label="最近调用">
        <span>{{ status.lastCallAt || '暂无调用记录' }}</span>
      </el-form-item>

      <el-form-item label="连接配置">
        <bl-row just="flex-start">
          <el-button type="primary" :disabled="!canCopyConfig" @click="copyConfig('http')">
            <span class="iconbl bl-copy-line"></span>复制 HTTP 配置
          </el-button>
          <el-button type="primary" plain :disabled="!canCopyConfig" @click="copyConfig('stdio')">
            <span class="iconbl bl-copy-line"></span>复制 stdio 配置
          </el-button>
          <el-button :disabled="!desktopAvailable" @click="rotateToken">轮换访问凭证</el-button>
        </bl-row>
        <div class="conf-tip">访问凭证不会返回到页面；复制动作由主进程直接写入系统剪贴板。轮换后，之前复制的配置会立即失效。</div>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@renderer/stores/user'
import { isElectron } from '@renderer/assets/utils/util'
import { mcpCopyConfig, mcpGetStatus, mcpRotateToken, mcpSetEnabled, mcpSetPort, type McpServiceStatus } from '@renderer/assets/utils/electron'

const userStore = useUserStore()
const desktopAvailable = isElectron()
const loading = ref(false)
const changingEnabled = ref(false)
const enabledInput = ref(false)
const portInput = ref(0)
const status = ref<McpServiceStatus>({
  enabled: false,
  running: false,
  state: 'disabled',
  host: '127.0.0.1',
  port: 0,
  endpoint: '',
  authenticated: false,
  scopes: [],
  storageSecurity: desktopAvailable ? 'secure' : 'unavailable'
})

const stateText = computed(() => {
  const labels: Record<McpServiceStatus['state'], string> = {
    disabled: '已关闭',
    starting: '启动中',
    'waiting-auth': '等待登录信息',
    running: '运行中',
    stopping: '关闭中',
    error: '运行错误'
  }
  return labels[status.value.state]
})

const canCopyConfig = computed(() => desktopAvailable && status.value.running && status.value.authenticated)

const applyStatus = (nextStatus: McpServiceStatus) => {
  status.value = nextStatus
  enabledInput.value = nextStatus.enabled
  portInput.value = nextStatus.port
}

const reload = async (silent = true) => {
  if (!desktopAvailable) return
  if (!silent) loading.value = true
  try {
    applyStatus(await mcpGetStatus())
  } catch (error: any) {
    if (!silent) ElMessage.error(error?.message || '无法读取 AI 工具接入状态')
  } finally {
    loading.value = false
  }
}

const handleEnabled = async (enabled: string | number | boolean) => {
  const shouldEnable = Boolean(enabled)
  if (shouldEnable && status.value.storageSecurity !== 'secure') {
    enabledInput.value = false
    ElMessage.error('当前系统无法安全存储凭证，AI 工具接入无法启用')
    return
  }
  changingEnabled.value = true
  try {
    if (shouldEnable) {
      await ElMessageBox.confirm(
        '启用后，持有连接配置的本机 AI 工具将能按授权读取或修改你的笔记。请确认本机环境可信，并妥善保管连接配置。',
        '确认启用 AI 工具接入？',
        { confirmButtonText: '确认启用', cancelButtonText: '保持关闭', type: 'warning', draggable: true }
      )
    }
    applyStatus(await mcpSetEnabled(shouldEnable))
    ElMessage.success(shouldEnable ? 'AI 工具接入已启用' : 'AI 工具接入已关闭')
  } catch (error: any) {
    await reload(true)
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error?.message || '修改服务状态失败')
    }
  } finally {
    changingEnabled.value = false
  }
}

const savePort = async () => {
  if (!Number.isInteger(portInput.value) || portInput.value < 1024 || portInput.value > 65535) {
    ElMessage.warning('端口需为 1024 至 65535 之间的整数')
    return
  }
  loading.value = true
  try {
    applyStatus(await mcpSetPort(portInput.value))
    ElMessage.success('监听端口已保存')
  } catch (error: any) {
    ElMessage.error(error?.message || '保存监听端口失败')
    await reload(true)
  } finally {
    loading.value = false
  }
}

const rotateToken = async () => {
  try {
    await ElMessageBox.confirm('轮换后，所有已复制到 AI 工具中的旧连接配置都会失效。', '确认轮换访问凭证？', {
      confirmButtonText: '确认轮换',
      cancelButtonText: '取消',
      type: 'warning',
      draggable: true
    })
    applyStatus(await mcpRotateToken())
    ElMessage.success('访问凭证已轮换，请重新复制连接配置')
  } catch (error: any) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error?.message || '轮换访问凭证失败')
    }
  }
}

const copyConfig = async (kind: 'http' | 'stdio') => {
  try {
    const result = await mcpCopyConfig(kind)
    if (result.copied) ElMessage.success(`${kind === 'http' ? 'HTTP' : 'stdio'} 配置已复制，请仅粘贴到受信任的 AI 工具`)
  } catch (error: any) {
    ElMessage.error(error?.message || '复制连接配置失败')
  }
}

let refreshTimer: NodeJS.Timeout | undefined
onMounted(() => {
  reload(false)
  refreshTimer = setInterval(() => reload(true), 5000)
})
onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
})

defineExpose({ reload })
</script>

<style scoped lang="scss">
@import './styles/config-root.scss';

.ai-config-root {
  max-width: 900px;

  .status-alert {
    max-width: 820px;
    margin-top: 12px;
  }

  .refresh-btn,
  .port-save {
    margin-left: 10px;
  }

  code {
    color: var(--el-color-primary);
  }
}
</style>
