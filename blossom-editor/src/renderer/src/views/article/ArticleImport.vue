<template>
  <div class="article-import-root">
    <!-- 标题 -->
    <div class="info-title">
      <div class="iconbl bl-file-upload-line"></div>
      导入文章
    </div>

    <div class="content">
      <el-upload
        ref="uploadRef"
        v-model:file-list="uploadFiles"
        name="file"
        multiple
        :action="serverStore.serverUrl + articleImportApiUrl"
        :data="{ pid: porps.doc.i, batchId: importBatch }"
        :headers="{ Authorization: 'Bearer ' + userStore.auth.token }"
        :on-change="onChange"
        :show-file-list="true"
        :before-upload="beforeUpload"
        :on-success="onUploadSuccess"
        :on-error="onUploadError"
        :auto-upload="false">
        <template #trigger>
          <el-button size="default">选择文章</el-button>
        </template>
        <el-button style="margin-left: 10px" size="default" type="primary" @click="submitUpload" plain>开始导入</el-button>
        <template #tip>
          <div class="el-upload__tip upload-tip">仅支持导入 [md，txt] 后缀的文件。</div>
        </template>
      </el-upload>
    </div>
  </div>
</template>

<script setup lang="ts">
import { PropType, ref } from 'vue'
import type { UploadFile, UploadFiles, UploadInstance, UploadProps, UploadUserFile } from 'element-plus'
import { articleImportApiUrl } from '@renderer/api/blossom'
import { useUserStore } from '@renderer/stores/user'
import { useServerStore } from '@renderer/stores/server'
import { uuid } from '@renderer/assets/utils/util'
import Notify from '@renderer/scripts/notify'
import { onChange, beforeUpload, handleUploadSuccess, handleUploadError } from './scripts/article-import'

const userStore = useUserStore()
const serverStore = useServerStore()

const porps = defineProps({
  doc: {
    type: Object as PropType<DocTree>,
    required: true
  }
})

const emits = defineEmits<{
  imported: [articles: DocInfo[]]
}>()

const importBatch = ref(uuid())
const uploadRef = ref<UploadInstance>()
const uploadFiles = ref<UploadUserFile[]>([])
const importedArticles = ref<DocInfo[]>([])
let importedEmitted = false

const emitImportedWhenComplete = (files: UploadFiles) => {
  const completed = files.every((file) => file.status === 'success' || file.status === 'fail')
  if (completed && importedArticles.value.length > 0 && !importedEmitted) {
    importedEmitted = true
    emits('imported', importedArticles.value)
  }
}

const onUploadSuccess: UploadProps['onSuccess'] = (resp, file: UploadFile, files: UploadFiles) => {
  if (handleUploadSuccess(resp)) {
    file.status = 'success'
    importedArticles.value.push({ ...resp.data, type: 3 })
  } else {
    file.status = 'fail'
  }
  emitImportedWhenComplete(files)
}

const onUploadError: UploadProps['onError'] = (error, file, files) => {
  file.status = 'fail'
  handleUploadError(error)
  emitImportedWhenComplete(files)
}

const submitUpload = () => {
  if (!uploadFiles.value.some((file) => file.status === 'ready')) {
    Notify.error('请先选择文章', '上传失败')
    return
  }
  importedArticles.value = []
  importedEmitted = false
  importBatch.value = uuid()
  uploadRef.value!.submit()
}
</script>

<style scoped lang="scss">
@import '@renderer/assets/styles/bl-dialog-info';

.article-import-root {
  border-radius: 10px;

  .content {
    padding: 20px;

    // .upload-tip {
    //   border: 1px solid var(--el-border-color);
    //   padding: 5px 10px;
    //   border-radius: 5px;
    //   color: rgb(188, 55, 55);
    // }

    // .article-upload {
    //   :deep(.el-upload-list) {
    //     li {
    //       transition: none;
    //       margin-bottom: 0;
    //     }
    //   }
    // }
  }
}
</style>
