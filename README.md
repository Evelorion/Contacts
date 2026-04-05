# 通讯录私密增强版
<img alt="Logo" src="graphics/icon.webp" width="120" />

这是基于 Fossify Contacts 修改后的个人版本，重点放在联系人隐私保护和界面优化。

## 版本说明

这个仓库展示的是我修改后的版本，不是原版仓库首页说明。

本版本主要目标：

- 阻止第三方应用直接读取私密联系人
- 允许同签名的自家应用正常访问私密联系人
- 提供联系人私有化迁移能力
- 优化主界面、联系人列表、设置页的视觉效果

## 主要修改内容

### 1. 私密联系人权限保护

- 新增 `org.fossify.permission.READ_PRIVATE_CONTACTS` 的 `signature` 级别权限
- 给通讯录 `ContentProvider` 增加读取权限限制
- 增加 `PrivacyGuard` 校验调用方签名与包名
- 未授权访问返回空 `Cursor`，避免读取方闪退

### 2. 私密联系人写入逻辑

- 隐私保护开启后，新建联系人默认写入私有存储
- 编辑联系人时优先保持在私有存储
- 导入 VCF 联系人时优先导入私有存储
- 联系人主列表和选择器优先展示私密联系人

### 3. 旧联系人迁移

- 设置页新增迁移入口
- 可以把原来落在公开通讯录中的联系人迁移到私有存储
- 迁移后，第三方通讯录应用将无法继续直接读取这些私密联系人

### 4. 界面优化

- 主界面增加隐私保护状态提示
- 设置页增加隐私保护说明与操作入口
- 联系人列表改成更清晰的卡片式样
- 统一优化圆角、间距、阴影与留白

## 关键修改入口

- `app/src/main/AndroidManifest.xml`
- `app/src/main/kotlin/org/fossify/contacts/contentproviders/MyContactsContentProvider.kt`
- `app/src/main/kotlin/org/fossify/contacts/helpers/PrivacyGuard.kt`
- `app/src/main/kotlin/org/fossify/contacts/activities/SettingsActivity.kt`
- `app/src/main/kotlin/org/fossify/contacts/activities/EditContactActivity.kt`
- `app/src/main/kotlin/org/fossify/contacts/dialogs/ImportContactsDialog.kt`

## 发行说明

GitHub Release 中上传的是当前修改版构建产物。

注意：

- 当前 Release 附件为 `unsigned` APK
- 原因是当前构建环境没有正式签名证书
- 如果需要可直接安装的正式版，需要再使用你自己的签名证书重新打包

## 仓库说明

- 默认分支：`private-ui-edition`
- 这个分支保存的是我当前这套隐私增强和界面优化修改
- 原版 Fossify 项目请以官方仓库为准

<div align="center">
<img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_en-US.png" width="30%">
<img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_en-US.png" width="30%">
<img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/3_en-US.png" width="30%">
</div>
