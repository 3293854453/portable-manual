# 自动裁边：实现细节与踩过的坑

> 这是开发笔记，记录「自动裁边」这一块为什么是现在这个样子。
> 只关心怎么用 App 的话，看 [README](../README.md) 就够了。

## 总体结构：四层引擎，逐级回退

```
detect()
 ├─ 0. HedEdgeDetector      ← HED 神经网络（首选）
 ├─ 1. HSV 区域 + 多阈值投票
 ├─ 2. 自适应阈值
 └─ 3. Canny 边缘
```

`DocumentScanner` 是门面，`OpenCvRuntime` 负责原生库加载，加载失败时还有一套
纯 Kotlin 实现兜底（`HeuristicDetector`）。任何一层不可用都不会让功能消失。

## 为什么最终上了神经网络

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

### 模型来源与体积

模型来自开源项目 [SmartCropper](https://github.com/pqpo/SmartCropper)（**Apache-2.0**），
是 MobileNetV2 backbone 的量化 HED：

```
app/src/main/assets/models/hed_lite_model_quantize.tflite   339 KB
```

加上 TensorFlow Lite 运行时，APK 从 23MB 涨到约 25MB。

两个反直觉的实现细节（照抄原项目的 `ImageDetector.java`，写错会得到全黑输出）：

- 输入 `256×256×3 float32`，值域是 **0-255 而不是 0-1**（未归一化）
- 输出是 **logit 而不是概率**（范围约 -21~10），阈值 0.2 作用在 logit 上，约等于概率 0.55
- 输入张量是 **3 维**（没有 batch 维）

### 一个必须知道的坑

HED 输出的是一条**细闭合环**。所以**不能按 `contourArea` 选最大轮廓** ——
环一旦有断口，`contourArea` 会退化成「两端点连线围出的面积」，完全无意义
（实测会选出只覆盖一角的小框）。

正确做法：遍历所有轮廓，按**凸包面积**筛选，取最大的合法四边形。

### 四边形精化

HED 边缘图只有 256×256，凸包 + 多边形逼近得到的角点被量化到 1/256 ≈ 0.4%，
还会被边缘粗细和毛刺带偏。

改为：收集边缘像素 → 对四条边各取带宽 3px 内的边缘点 → **最小二乘拟合直线**
（`Imgproc.fitLine`）→ 取**相邻直线交点**作为新顶点 → 迭代 3 次。

实测边缘拟合残差平均改善 **5~24%**，得到亚像素精度。

## 传统三层路径（HED 不可用时的兜底）

| 优先级 | 路径 | 适用场景 |
| --- | --- | --- |
| 1 | **HSV 区域 + 多阈值投票** | 彩色背景 |
| 2 | **自适应阈值** | 「整幅画面都接近灰阶」—— 饱和度中位数趋近 0 时 HSV 失效 |
| 3 | **Canny 边缘** | 最后兜底 |

**多阈值投票**：纸张的「白」并不统一（纯白打印纸 S≈3，米色手写本 S≈9~13），
单一固定阈值必然顾此失彼。跑三个饱和度系数（0.35 / 0.50 / 0.70），
只有至少两个给出相互一致的四边形才采信；不一致就降级。

> **设计原则：框错对象比不框更糟。** 不一致时宁可放弃，让用户手动拖角。

## 纯 Kotlin 兜底实现

OpenCV 原生库加载失败时（个别定制 ROM）自动接管：

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

## 走过的弯路（值得记录）

这些都是「看起来合理」但**实测无效**的方案，别再走一遍：

- 「几何分最高的候选」选结果 → **总是选到面积更大的过检框**。
  因为「对边平衡度」偏向大而方正的框，而真实纸张是透视倾斜的，平衡度天然偏低
- 「边缘支持度」判据 → **更差**。木纹本身有大量边缘，大框照样"有边"
- 「边界对比度」判据 → **区分不开**（正确的 0.144 vs 过检的 0.138）
- CLAHE 增强后再 Canny → **更差**。木纹纹理被放大成几百条假边缘
- 收敛形态学核 → **无效**，且把已经正确的样本搞坏

**教训：判据必须在真实样本上验证，不能想当然。**

## 评估过但没采用的模型

**PiDiNet（ICCV 2021）** —— 比 HED 新 6 年，模型 2.8MB，专为 CPU 实时设计。

实测结论是**不用**：它是自然图像数据集（BSDS500）上训的通用边缘检测，
不知道「文档」是什么 —— 对背景、手、木纹都有大量响应，输出的纸张轮廓粗且有断口，
7 张样本的四边形面积跑到 90%+（把背景都框进去了）。

而当前这个 339KB 的 HED 是**合成文档样本**上训的，专门认纸张边界。

**教训：领域专用的小模型 > 通用的大模型。**

> PiDiNet 的 `-small-l` / `-tiny-l` 权重与官方模型定义的通道数对不上，
> 只有主模型 `table5_pidinet` 能正确加载 —— 这是官方仓库的一个小坑。

## 关于体积

OpenCV 的 `libopencv_java4.so` 是**单体库**，把 dnn / features2d / calib3d / stitching
全部模块都编了进去，而实际只用到 imgproc 里十来个函数：

| ABI | 体积 |
| --- | --- |
| arm64-v8a | 23.5 MB |
| armeabi-v7a | 15.6 MB |

所以做了两件事：

1. `packaging { jniLibs { useLegacyPackaging = true } }` —— 压缩存储原生库
   （AGP 8 默认不压缩，这一步把 APK 从 60MB 压到 39MB debug / 31MB release）
2. **只保留 arm64-v8a** —— minSdk 26 的设备几乎全是 arm64

release APK 约 **24MB**。需要兼容 32 位老设备的话，在 `app/build.gradle.kts` 的
`abiFilters` 里加回 `"armeabi-v7a"` 即可（约 +7MB）。

> 想压到 20MB 以内，唯一正解是用 NDK 自编精简版 OpenCV
> （`-DBUILD_LIST=core,imgproc`），预计 3~4MB/ABI。本项目暂未这么做。

## 相关源码

- [`DocumentScanner.kt`](../app/src/main/java/com/portablemanual/app/image/DocumentScanner.kt) —— 门面，多引擎调度
- [`HedEdgeDetector.kt`](../app/src/main/java/com/portablemanual/app/image/HedEdgeDetector.kt) —— TFLite 推理
- [`OpenCvDetector.kt`](../app/src/main/java/com/portablemanual/app/image/OpenCvDetector.kt) —— 传统三层路径
- [`HeuristicDetector.kt`](../app/src/main/java/com/portablemanual/app/image/HeuristicDetector.kt) —— 纯 Kotlin 兜底
