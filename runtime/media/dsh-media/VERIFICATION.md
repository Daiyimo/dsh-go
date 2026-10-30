# 0.2.0 验证与复现

运行环境：Linux，Node v24.21.0，DSH npm `0.2.1-rc.4`，`TZ=Asia/Shanghai`。现有 rc.4 runtime lock 的 628 个非根记录全部保留，只加入精确 integrity 的 `dsh-llm-mock-server@0.2.1-rc.4` 与 `image-size@2.0.2`；不混用其他版本的 jobs。

## 复现

从仓库根执行，`/absolute/media-deps` 与 `/absolute/media-evidence` 必须是新目录。安装步骤不运行 npm 生命周期脚本；实际工作区的 node_modules 已存在时不要覆盖，先核对其来源。

```sh
node runtime/media/dsh-media/test/headless-v2/prepare.mjs --out /absolute/media-deps
npm ci --ignore-scripts --prefix /absolute/media-deps
ln -s /absolute/media-deps/node_modules runtime/media/dsh-media/node_modules
TZ=Asia/Shanghai node --test runtime/media/dsh-media/test/*.test.js
TZ=Asia/Shanghai node runtime/media/dsh-media/test/headless-v2/run.mjs --out /absolute/media-evidence
```

共享开发机应串行运行测试 / headless，并为每个工作区指定独立的 TMPDIR。prepare 生成脚本只写独立目录，不重解析或修改 runtime 锁；输出 test-inputs.json 记录基准哈希和保留条目数。

## 已执行与证据范围

- 全套 Node：**306 / 306 通过**，含既有 M0、预算账本、HTTP / 文件安全回归和新增 provider / jobs 接线用例。
- 本地真实 HTTP：OpenAI、方舟、百炼、硅基流动的图片及编辑；三家文生 / 图生视频、实际 DELETE / POST 取消、运行中不可取消、失败、提交超时、查询超时。接受 POST 后 socket 断开只提交一次并保持 submission_unknown。下载保持原件字节，不带服务密钥。
- 安全边界：精确参考文件审批、审批后替换身份拒绝、FIFO 拒绝、JSON 转义膨胀的元数据在 POST 前拒绝、预算跨请求、默认图片服务下调用视频、原会话恢复。真实子进程在发布 MP4 后、写索引前 `process.exit(77)`，随后新进程状态下用新文件名续查发布，旧文件保留、供应商 POST 为零。
- 真实未改动 rc.4 公共 CLI：图片与视频各一条实际 Session，经过生产 admission / approval / ledger；mock seam 只替换 HTTP 目标，不替换 generate 或跳过费用审批。视频使用真正的 jobs.start、完成 producer 状态与 tool-jobs 通知，随后真正执行 get_media_result，初始和终态 rid/job 一致。

headless 输出包含 identity.json（Node、CLI、源码与测试锁哈希）、summary.json、每案 wire-audit.json、Session 原始事件、物理 V4 JSONL 和 verified.json。验证连续 seq、原始事件与持久事件逐对象一致、正常退出、无假凭据回显、输出与 fixture 字节一致。日志保留私有测试工作区绝对路径，不改写成手机路径，也不复制为 App 的 live fixture。

headless rc.4 单次运行不自动等待普通 jobs。测试让第二次本地 mock LLM 响应等待下载完成后再结束当前 turn，确保真实通知进入仍存活的 Host；不调用会标记 awaited 并抑制通知的 jobs.wait / job_output(wait:true)。这验证真实 Host 接线，不证明任意 one-shot headless 进程会自行等到视频结束。

视频 fixture 是本地合成 32×24 蓝色 1 秒 H.264 MP4，无第三方素材；生成命令：

```sh
ffmpeg -f lavfi -i color=c=blue:s=32x24:r=1:d=1 -c:v libx264 -pix_fmt yuv420p -movflags +faststart synthetic.mp4
base64 -w0 synthetic.mp4 > runtime/media/dsh-media/test/fixtures/video.base64
```

## 未验证 / 有意限制

未调用真实付费服务；接口字段来自 data/models.json 列出的官方资料，不证明账号权限或实际图像质量。未执行 Android / PRoot / App 媒体完整链路、真实模型语言理解、真实 settings 表单写入 reload、跨 Host 部署。旧 test/headless 的 M0 验证材料仅作历史记录，当前入口为 headless-v2。

当前报价未知，逐次审批与本地预留不能保证供应商账单不超过 ¥20；没有退款推断。Host 重启需原会话显式 resume_media，不自动重建任务；无 task ID 的不确定提交不可自动恢复。恢复新命名避免覆盖半发布遗留，崩溃产生的旧文件与 .pending 目录不自动删除。MP4 只做格式与实际头部元数据验证，不保证每台设备解码。方舟/百炼只确认排队取消，硅基取消未提供。

npm pack 只做本地内容检查与解包 import；npm 发布、市场上线、推送与设备安装均不在本验证范围。
