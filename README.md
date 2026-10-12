# 便携说明书

把纸质说明书拍下来，随时能查。

一个**完全离线**的 Android 应用：纸质说明书拍照归档 → 自动裁边拉正 → 文件夹 + 标签双轨组织 → 关键词搜索 → 导出 PDF / 整库备份。

没有账号，没有云端，没有网络权限。数据只存在你自己的手机上。

---

## 为什么做这个

家里每买一件电器就多一本说明书，翻两页塞进抽屉，之后再想查「滤芯型号」「怎么拆洗」就再也找不到了。

市面上的说明书 App 大多要注册、要上传、要联网。这个项目反过来：**离线优先，数据主权归你**。

## 功能

### 拍照与图像处理

- **连续多页拍摄**：拍完一页接着拍下一页，一气呵成
- **自动裁边拉正**：识别纸张四角并做透视变换，拍歪了也能救回来
- **手动微调**：自动识别不准时，四个角点可以直接拖
- **自动压缩**：最长边 1600px / JPEG 质量 80，一张 3MB 的原图压到约 250KB
- **相册导入**：已有照片可直接导入，走同一条处理管线
- **导出 PDF**：多页合并成一份 A4 PDF

### 组织与检索

- **文件夹**：多级层级（家电 / 厨房 / 冰箱），长按可重命名、删除
- **标签**：一份设备可打多个标签，交叉筛选
- **关键词搜索**：匹配名称、品牌、型号、备注、标签

### 数据安全

- **整库备份**：导出 `.zip`（`manifest.json` + 全部图片），换机一键迁移
- **备份还原**：支持「合并导入」与「覆盖导入」两种模式
- **存储统计与残留清理**

### 阅读

- 全屏阅读器，左右滑动翻页，双指缩放

## 设计

**黑白灰**。说明书本身是花花绿绿的印刷品，界面越素，内容越突出。

- Material 3，动态深浅色主题
- 无彩色干扰，标签靠灰阶深浅区分
- 交互优先：跟手的拖拽、克制的动画、明确的空状态

## 技术栈

| 层 | 选型 |
| --- | --- |
| 语言 | Kotlin 2.0 |
| UI | Jetpack Compose + Material 3 |
| 架构 | MVVM + Repository |
| 数据库 | Room |
| 偏好 | DataStore |
| 相机 | CameraX |
| 图片加载 | Coil |
| 依赖注入 | 手写 `AppContainer`（不引入 Hilt，减少构建复杂度） |
| 边缘检测 | **HED 神经网络**（TensorFlow Lite，339KB）+ OpenCV 传统管线 + 纯 Kotlin 兜底 |

### 自动裁边是怎么做的

**四层引擎，逐级回退**。核心是最后加上的第 0 层 —— 神经网络。

```
detect()
 ├─ 0. HedEdgeDetector      ← HED 神经网络（首选）
 ├─ 1. HSV 区域 + 多阈值投票
 ├─ 2. 自适应阈值
 └─ 3. Canny 边缘
```

#### 为什么最终上了神经网络

前三个版本一直在 Canny / HSV / 自适应阈值之间调参，**始终卡在同一个地方**：

> **Canny 找的是「像素梯度」，不是「物体边界」。**

所以木纹、阴影、印刷内容全都变成边缘，而真正的纸张边界反而因为对比度低、
被手遮挡而断裂 —— 断裂的边永远闭不成环，`findContours` 自然找不到纸张。

HED（Holistically-Nested Edge Detection）是训练出来的边缘检测网络，
学的是**语义边界**：它知道「这里是纸的边缘」，而不是「这里像素变化快」。

同一批样本上的对比：

| | Canny | HED |
| --- | --- | --- |
| 纸张轮廓 | 断裂、布满木纹噪点 | **完整闭合的干净矩形** |
| 深色封面 | 找不到 | **准确框出** |
| 低对比（白纸压浅灰桌面） | 找不到 | **准确框出** |
| 7 张真实样本 | 5/7 | **7/7** |

#### 模型来源与体积

模型来自开源项目 [SmartCropper](https://github.com/pqpo/SmartCropper)（**Apache-2.0**），
是 MobileNetV2 backbone 的量化 HED：

```
app/src/main/assets/models/hed_lite_model_quantize.tflite   339 KB
```

**只有 339KB** —— 加上 TensorFlow Lite 运行时，APK 从 23MB 涨到约 25MB。

两个反直觉的实现细节（照抄原项目的 `ImageDetector.java`，写错会得到全黑输出）：

- 输入 `256×256×3 float32`，值域是 **0-255 而不是 0-1**（未归一化）
- 输出是 **logit 而不是概率**（范围约 -21~10），阈值 0.2 作用在 logit 上，约等于概率 0.55

#### 一个必须知道的坑

HED 输出的是一条**细闭合环**。所以**不能按 `contourArea` 选最大轮廓** ——
环一旦有断口，`contourArea` 会退化成「两端点连线围出的面积」，完全无意义
（实测会选出只覆盖一角的小框）。

正确做法：遍历所有轮廓，按**凸包面积**筛选，取最大的合法四边形。

#### 传统三层路径（HED 不可用时的兜底）

| 优先级 | 路径 | 适用场景 |
| --- | --- | --- |
| 1 | **HSV 区域 + 多阈值投票** | 彩色背景 |
| 2 | **自适应阈值** | 「整幅画面都接近灰阶」—— 饱和度中位数趋近 0 时 HSV 失效 |
| 3 | **Canny 边缘** | 最后兜底 |

**多阈值投票**：纸张的「白」并不统一（纯白打印纸 S≈3，米色手写本 S≈9~13），
单一固定阈值必然顾此失彼。跑三个饱和度系数（0.35 / 0.50 / 0.70），
只有至少两个给出相互一致的四边形才采信；不一致就降级。

> **设计原则：框错对象比不框更糟。** 不一致时宁可放弃，让用户手动拖角。

#### 走过的弯路（值得记录）

- 「几何分最高的候选」选结果 → **总是选到面积更大的过检框**。
  因为「对边平衡度」偏向大而方正的框，而真实纸张是透视倾斜的，平衡度天然偏低
- 「边缘支持度」判据 → **更差**。木纹本身有大量边缘，大框照样"有边"
- 「边界对比度」判据 → **区分不开**（正确的 0.144 vs 过检的 0.138）
- CLAHE 增强后再 Canny → **更差**。木纹纹理被放大成几百条假边缘
- 收敛形态学核 → **无效**，且把已经正确的样本搞坏

这些判据/预处理都是「看起来合理」但实测无效的。**判据必须在真实样本上验证。**

OpenCV 原生库加载失败时（个别定制 ROM）自动接管，功能不会消失：

1. 缩到最长边 560px，生成四路候选掩膜（背景差 / 亮区 / 暗区 / Sobel 边缘）
   - **背景差**：取最外圈像素的 **RGB 中位数**当桌面底色，再对颜色距离做 Otsu 自适应阈值 ——
     比颜色而不是比亮度
2. 每路取最大连通域（8 邻域），只保留**每行最左/最右**像素作为凸包候选
   （同行的其余点必在两点连线上，不改变凸包结果，却把候选点从几十万降到几千）
3. **Andrew's monotone chain 凸包** → **Douglas-Peucker 多边形逼近**
   （与 OpenCV 的 `approxPolyDP` 是同一套数学）
4. 打分 = 面积占比 × (对边均衡度 + 边缘支持度)，门槛 0.30，宁可返回 null 也不给错的结果

透视变换用 `Matrix.setPolyToPoly()`，与 OpenCV 的 `warpPerspective` 数学等价。

设置页「关于」里可以看到当前跑的是哪个引擎。

#### 关于体积

OpenCV 的 `libopencv_java4.so` 是**单体库**，把 dnn / features2d / calib3d / stitching
全部模块都编了进去，而我们只用到 imgproc 里十来个函数：

| ABI | 体积 |
| --- | --- |
| arm64-v8a | 23.5 MB |
| armeabi-v7a | 15.6 MB |

所以本仓库做了两件事：

1. `packaging { jniLibs { useLegacyPackaging = true } }` —— 压缩存储原生库
   （AGP 8 默认不压缩，这一步把 APK 从 60MB 压到 39MB debug / 31MB release）
2. **只保留 arm64-v8a** —— minSdk 26 的设备几乎全是 arm64

release APK 约 **24MB**。需要兼容 32 位老设备的话，在 `app/build.gradle.kts` 的
`abiFilters` 里加回 `"armeabi-v7a"` 即可（约 +7MB）。

> 想压到 20MB 以内，唯一正解是用 NDK 自编精简版 OpenCV
> （`-DBUILD_LIST=core,imgproc`），预计 3~4MB/ABI。本项目暂未这么做。

详见 [`DocumentScanner.kt`](app/src/main/java/com/portablemanual/app/image/DocumentScanner.kt)、
[`OpenCvDetector.kt`](app/src/main/java/com/portablemanual/app/image/OpenCvDetector.kt)、
[`HeuristicDetector.kt`](app/src/main/java/com/portablemanual/app/image/HeuristicDetector.kt)。

## 权限

只申请一个：

- `CAMERA` —— 拍照

读取相册走系统 Photo Picker，**不需要任何存储权限**。Manifest 里**没有** `INTERNET` 权限 —— 从权限层面证明这个应用不可能联网。

## 构建

### 环境要求

- JDK 17
- Android SDK：`platforms;android-35`、`build-tools;35.0.0`
- Gradle 8.11.1（仓库自带 wrapper）

### 步骤

```bash
git clone <repo-url>
cd portable-manual

# 指向你的 Android SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### release 签名

在项目根目录建 `keystore.properties`（已在 `.gitignore` 中，不会入库）：

```properties
storeFile=/absolute/path/to/release.jks
storePassword=******
keyAlias=portable-manual
keyPassword=******
```

生成 keystore：

```bash
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias portable-manual
```

然后：

```bash
./gradlew :app:assembleRelease
```

### 国内网络

Gradle 发行包官方源偶发 502，可改用镜像。编辑 `gradle/wrapper/gradle-wrapper.properties`：

```properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip
```

## 项目结构

```
app/src/main/java/com/portablemanual/app/
├── data/
│   ├── local/           Room 实体、DAO、数据库
│   ├── prefs/           DataStore 设置
│   └── repository/      LibraryRepository（唯一数据出入口）
├── image/               ImageStore / ImageCompressor / DocumentScanner
├── pdf/                 PdfExporter
├── backup/              BackupManager（zip 备份与还原）
├── di/                  AppContainer
└── ui/
    ├── theme/           黑白灰设计系统
    ├── library/         资料库（搜索 + 文件夹 + 标签筛选 + 卡片网格）
    ├── detail/          设备详情（多页预览 + 排序 + 导出）
    ├── editor/          设备编辑
    ├── capture/         拍照 + 裁剪调整
    ├── reader/          全屏阅读
    ├── tags/            标签管理
    └── settings/        设置与备份
```

## 数据模型

```
Folder (自引用树)
Device  1:1  Document  1:N  Page
Device  N:N  Tag
```

图片存在私有目录 `files/images/{deviceId}/{uuid}.jpg`，数据库只存**相对路径** —— 所以备份包换到任何手机都能完整还原。

## 已知局限

- 自动裁边是启发式 + CV 的混合方案，**纸张与桌面颜色和亮度都几乎相同**（如纯白纸压在纯白纸上）时仍可能失败，此时请手动拖角
- v1 不做 OCR，搜索范围限于你手填的结构化字段（名称/品牌/型号/备注/标签）
- 没有云端，换手机必须手动导出/导入备份包
- 只支持 arm64-v8a 设备（minSdk 26 的设备几乎都是）
- release 版暂未开启 R8 混淆，优先保证真机稳定（开启后 Java/Kotlin 部分可再瘦一些，原生库体积不变）

## 更新日志

### v1.2.1
- **新增四边形精化**：HED 边缘图只有 256×256，凸包 + 多边形逼近得到的角点被量化到
  1/256 ≈ 0.4%，还会被边缘粗细和毛刺带偏。现在改用**最小二乘拟合四条边直线、
  取相邻直线交点**作为新顶点，得到亚像素精度。实测边缘拟合残差平均改善 **5~24%**
- **评估过 PiDiNet（ICCV 2021）但未采用**：它是自然图像数据集上训的通用边缘检测，
  不知道「文档」是什么 —— 对背景、手、木纹都有大量响应，输出的纸张轮廓粗且有断口，
  7 张样本的四边形面积跑到 90%+（把背景都框进去了）。
  而当前这个 339KB 的 HED 是**合成文档样本**上训的，专门认纸张边界

### v1.2.0
- **引入 HED 神经网络做边缘检测**（TensorFlow Lite，模型仅 339KB）。
  根本原因是前几版一直卡在同一个地方：**Canny 找的是「像素梯度」而不是「物体边界」**
- 实测 7 张真实样本：**从 5/7 提升到 7/7**
- 模型来自开源项目 [SmartCropper](https://github.com/pqpo/SmartCropper)（Apache-2.0）
- 传统三层路径（HSV 区域投票 / 自适应阈值 / Canny）保留为兜底
- 体积：release APK 23MB → 约 25MB

### v1.1.3
- **修复「框错对象」**：纸张的白并不统一（纯白打印纸 S≈3，米色手写本 S≈9~13），
  固定紧阈值会框住旁边的白纸印刷书。改为**多阈值一致性投票**
- **修复面积上限与相机提示矛盾**：相机页让用户「尽量拍满画面」，
  但上限设的是 0.60，照做反而被判定为不可信。放宽到 0.92

### v1.1.2
- **修复「整幅画面都接近灰阶」时裁边完全失效**。新增**自适应阈值**作为第二优先级路径
- **修复候选选择策略错误**：原先按「几何分最高」跨阈值选结果，而「对边平衡度」
  偏向面积更大的框，导致总是选到过检结果。改为固定优先级回退

### v1.1.1
- **修复自动裁边在真实场景下完全失效**。根因三个：检测路径选错（Canny 边缘会断裂）、
  面积门槛过高（0.15 → 0.06）、缺少凸包预处理

### v1.1.0
- **自动裁边改用 OpenCV**
- 新增**双引擎降级**：OpenCV 原生库加载失败时自动退回纯 Kotlin 实现
- 纯 Kotlin 实现补齐 **凸包 + Douglas-Peucker 多边形逼近**
- 体积：release APK 约 23MB（仅 arm64-v8a，原生库压缩存储）

### v1.0.1
- 修复：阅读器左右滑动无法翻页（手势处理器误吞了单指横向拖动）
- 修复：深色主题下新建的标签胶囊几乎不可见（灰阶配色未跟随主题）
- 修复：新建重名标签时静默无反馈（现在会提示，并自动选中已有标签）
- 调整：启动图标字形缩小到自适应图标安全区内，不再顶边

## 路线图

- [ ] OCR 全文检索（可选开关）
- [ ] 保修到期提醒
- [ ] 更多文档类型（保修卡、发票）
- [ ] 深色模式下的图片反色阅读

## 开源协议

[Apache-2.0](LICENSE)

## 需求文档

[`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md) —— 完整的需求规格说明书，记录了每一个设计决策的来由。
