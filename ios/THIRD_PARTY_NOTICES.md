# 第三方声明 / Third-party notices (iOS)

上游 `../THIRD_PARTY_NOTICES/` 中的声明同样适用于 iOS 版，其中与 iOS 相关的：

- LibRaw 0.22.3：按 CDDL 1.0 使用，见 `../THIRD_PARTY_NOTICES/LibRaw-LICENSE-CDDL.txt`、`LibRaw-COPYRIGHT.txt`、`LibRaw-SOURCE.txt`；源码位于 `../app/src/main/cpp/vendor/libraw`，未修改
- Little CMS 等由 `core` 数据派生的声明，见上游对应文件

iOS 版另外参照了：

## film-index-generator

胶片边框与索引图的几何（135 片基、齿孔间距与尺寸、边字位置与字号比例、120 片边）与边字颜色按冲洗工艺的取值，参照 film-index-generator，并用 Core Graphics 重新实现，未复制其代码、字体或纹理。

```
MIT License

Copyright (c) 2026 Judian99

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

（fork：https://github.com/ohsobrkn/film-index-generator ，原作者 Judian99）
