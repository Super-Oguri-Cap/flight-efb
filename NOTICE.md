# 第三方声明

本项目（飞行EFB）的源代码采用 MIT 许可，见 LICENSE。

本文件列出随仓库分发或作为依赖使用的第三方内容及其许可。

## 一、检查单数据

`app/src/main/assets/checklists.json` 由 `tools/build_checklists.py` 从下列来源转换生成。

**项目**：simchecklists
**地址**：https://github.com/rkaw92/simchecklists
**许可**：MIT License
**版权**：Copyright (c) 2024 jflores82

原始清单由 tibonev 编写、Swisskommando 校对，仓库 README 注明 for simulation purposes only。

本项目的改动：
1. 把 `--阶段--` 与 `项目=状态` 的纯文本格式转为 JSON；
2. 补充每个阶段名的中文解释（见 `tools/build_checklists.py` 里的 `PHASE_GLOSS`）；
3. 按机型分类（空客 / 波音 / 其他），并按飞行流程排列阶段顺序。

除以上改动外，条目文本与状态保持原文。

MIT 许可全文：

```
MIT License

Copyright (c) 2024 jflores82

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 二、QRH 数据

`app/src/main/assets/qrh.json` 中的 16 条处置条目由本项目自行整理，依据公开的 A320 ECAM
体系与波音记忆项目结构，不含任何空客（Airbus）或波音（Boeing）的受控文件内容。
（此说明仅针对 `qrh.json`；随附的 PDF 手册原件见第六节。）

## 三、运行时依赖

以下依赖以二进制形式打包进 APK，均为 Apache-2.0 许可：

| 依赖 | 版本 | 许可 |
|---|---|---|
| org.jetbrains.kotlin:kotlin-stdlib | 2.2.21 | Apache-2.0 |
| org.jetbrains.kotlinx:kotlinx-coroutines | 1.9.0 | Apache-2.0 |
| androidx.core:core-ktx | 1.17.0 | Apache-2.0 |
| androidx.lifecycle:* | 2.9.4 | Apache-2.0 |
| androidx.activity:activity-compose | 1.12.0 | Apache-2.0 |
| androidx.compose.ui:* | 1.9.2 | Apache-2.0 |
| androidx.compose.material3:material3 | 1.3.2 | Apache-2.0 |

这些库未被修改。Apache-2.0 许可全文见 https://www.apache.org/licenses/LICENSE-2.0

## 四、构建工具

Gradle、Android Gradle Plugin、Kotlin 编译器仅在构建时使用，不进入分发的 APK。

## 五、航图资料

本仓库不包含任何航图 PDF。航图由使用者自行准备，其版权归原发布机构（例如中国民用航空局
空中交通管理局）所有。本项目只处理文件名与目录结构，不复制、不再分发航图内容。

## 六、随附手册 PDF

`app/src/main/assets/docs/` 下的两份 PDF 随仓库分发，并打包进 APK：

| 文件 | 原件 | 大小 |
|---|---|---|
| `a320_normal_checklist.pdf` | A320 正常检查单 | 400,464 字节 |
| `juneyao_qrh.pdf` | 吉祥航空快速检查单（全机队版） | 18,014,568 字节 |

这两份资料由使用者提供，版权归原权利人，本项目未获其授权或认可，**不在 MIT 许可范围内**。
收录仅用于飞行模拟学习与文字版检查单条目对照，不得用于真实飞行或任何商业用途；
如权利人提出异议，将从仓库移除。仓库中原始中文名文件不随仓库分发，入库的是
`assets/docs/` 下的 ASCII 名副本（代码按这两个名字读取）。

## 七、免责声明

本项目仅供飞行模拟使用（for simulation only）。不得用于真实飞行。作者不对内容的准确性
作任何保证。
