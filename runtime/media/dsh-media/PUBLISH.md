# 发包准备与执行说明

当前版本 `@dsh-go/media@0.2.0` 只做本地准备，没有执行 publish。设置 namespace / bundle entry ID 保持 `dsh-media`；patch package name 为 `@dsh-go/media`。`files` 明确列出入口依赖闭包、模型 JSON、patch、README 和 MIT LICENSE，不含测试、凭据、产物、私有日志或开发依赖。MIT 延续既有 package.json 的许可证声明。

发布前需确认 npm `@dsh-go` 组织权限、版本未占用、代码授权与包内文件。市场候选仅在真实发布且检查通过后才进入可安装索引；不要使用假 integrity，也不发布空占位包。

## 本地验证和令牌发布

从仓库根按 VERIFICATION.md 准备依赖、运行全套测试与 headless；然后：

```sh
cd runtime/media/dsh-media
npm pack --dry-run --json
npm pack --pack-destination /absolute/private/release-dir
```

审查 tarball 内容和 SHA256 后，owner 可在本机选择短期、仅限该组织/包的 granular token。不要把真实令牌放入命令文本或仓库。以下步骤会真实发布，本文未执行：

```sh
umask 077
media_npmrc=$(mktemp)
trap 'rm -f "$media_npmrc"; unset NPM_TOKEN' EXIT
printf '%s\n' '//registry.npmjs.org/:_authToken=${NPM_TOKEN}' > "$media_npmrc"
read -rsp 'npm token: ' NPM_TOKEN
export NPM_TOKEN
NPM_CONFIG_USERCONFIG="$media_npmrc" npm whoami
NPM_CONFIG_USERCONFIG="$media_npmrc" npm publish /absolute/private/release-dir/dsh-go-media-0.2.0.tgz --access public
```

根据组织的 2FA / granular token 策略完成认证；不预设可以绕过组织策略。环境变量引用的 `.npmrc` 用法见 [npm token 文档](https://docs.npmjs.com/private-modules/ci-server-config/)。本机普通令牌发布不声称带 CI provenance。

## 可选 GitHub Actions provenance 模板（未启用）

本仓库目前是私有来源，**不能直接宣称满足公开 provenance 要求**。owner 须先决定公开插件源码仓库 / 镜像，在 package.json 填入与该公开仓库大小写一致的 `repository.url`；保持完整验证输入，重新跑测试。npm 的 provenance 要求公开源码和受支持的托管 runner；OIDC trusted publishing 的绑定也必须在 npm 侧配置。见 [provenance](https://docs.npmjs.com/generating-provenance-statements/) 与 [trusted publishing](https://docs.npmjs.com/trusted-publishers/)。

下面是保留本仓库路径的模板，只有 owner 决定发布方案后才复制进 `.github/workflows/`。使用受保护 environment、显式 dispatch，并将 action tag 换成经 owner 审核的精确 commit。若抽出独立插件仓库，先移入同一 rc.4 锁和 header golden，再调整路径。

```yaml
name: Publish media with provenance
on:
  workflow_dispatch:
permissions:
  contents: read
jobs:
  publish:
    runs-on: ubuntu-latest
    environment: npm-release
    permissions:
      contents: read
      id-token: write
    steps:
      - uses: actions/checkout@v6
      - uses: actions/setup-node@v6
        with:
          node-version: '24.x'
          registry-url: https://registry.npmjs.org
          package-manager-cache: false
      - name: Install exact test graph
        run: |
          node runtime/media/dsh-media/test/headless-v2/prepare.mjs --out "$RUNNER_TEMP/media-deps"
          npm ci --ignore-scripts --prefix "$RUNNER_TEMP/media-deps"
          ln -s "$RUNNER_TEMP/media-deps/node_modules" runtime/media/dsh-media/node_modules
      - name: Verify
        env:
          TZ: Asia/Shanghai
        run: |
          node --test runtime/media/dsh-media/test/*.test.js
          node runtime/media/dsh-media/test/headless-v2/run.mjs --out "$RUNNER_TEMP/media-evidence"
      - name: Check and publish
        working-directory: runtime/media/dsh-media
        run: |
          npm pack --dry-run --json
          npm publish --provenance --access public
        env:
          NODE_AUTH_TOKEN: ${{ secrets.NPM_TOKEN }}
```

此模板采用 npm 官方令牌 + provenance 模式。配置并验证 OIDC trusted publisher 后可按官方说明去掉 token；不能只删除环境变量就声称已配置成功。provenance 证明来源与构建，不代表代码无风险。模板未加入自动 tag / release 触发，当前分支没有可执行的发布 workflow。
