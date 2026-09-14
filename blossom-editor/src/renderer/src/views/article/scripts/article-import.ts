import type { UploadProps, UploadUserFile } from 'element-plus'
import Notify from '@renderer/scripts/notify'

export const onChange: UploadProps['onChange'] = (file: UploadUserFile, files: UploadUserFile[]) => {
  const suffix = file.name.substring(file.name.lastIndexOf('.')).toLowerCase()
  if (suffix !== '.txt' && suffix !== '.md') {
    files.splice(files.indexOf(file), 1)
    Notify.error(`不支持的文件类型: ${file.name}`, '上传失败')
    return
  }
  if (file.size === 0) {
    files.splice(files.indexOf(file), 1)
    Notify.error(`${file.name} 内容为空`, '上传失败')
    return
  }
  if (file.size != undefined && file.size / 1024 / 1024 > 10) {
    files.splice(files.indexOf(file), 1)
    Notify.error(`${file.name} 大小不能超过 10MB`, '上传失败')
  }
}

export const beforeUpload: UploadProps['beforeUpload'] = (rawFile) => {
  if (rawFile.size === 0) {
    Notify.error('文件内容为空', '上传失败')
    return false
  }
  if (rawFile.size / 1024 / 1024 > 10) {
    Notify.error('文件大小不能超过 10MB!', '上传失败')
    return false
  }
  return true
}

export const handleUploadSuccess = (resp: any): boolean => {
  if (resp.code === '20000') {
    return true
  }
  Notify.error(resp.msg, '上传失败')
  return false
}

export const handleUploadError = (error: Error) => {
  if (error.message != undefined) {
    try {
      const resp = JSON.parse(error.message)
      if (resp != undefined) {
        Notify.error(resp.msg, '上传失败')
      }
    } catch (_e) {
      Notify.error(error.message, '上传失败')
    }
  }
}
