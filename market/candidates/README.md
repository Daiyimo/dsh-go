# 暂未收录的首批候选

`candidates/` 不参与生成索引，不等于通过审核。需要解决原因后，将条目移入 `plugins/`，重新跑完整检查。

| 候选 | 2026-10-30 实查结果 | 后续 |
| --- | --- | --- |
| `@dsh-go/media@0.2.0` | npm 包查询 404；0.2.0 是本地开发目标版本，尚无可核验发布物 | 发布后核实精确版本及原生协议，再移入 plugins。官方身份已在维护者 policy allow-list 中预留 |
| `dsh-univer-office@0.3.9` | npm `dist.unpackedSize` 为 195946027 字节，超过 50 MiB 上限 | 作者减小包体，或维护者另行修改已公布政策；本次不豁免 |

其他社区页推荐项的实际固定版本见 `plugins/`。只有 `npm run build` 完成后产生的 `dist/audit.json` 能说明某次检查的结果。
