# AIBuilding

基于 OpenAI 兼容协议的 Minecraft 自动建筑插件。向 AI 描述你想要的建筑，AI 将通过调用服务器内的工具自动完成建造。

## 特性

- **OpenAI 兼容接口**：支持 OpenAI 官方及任意兼容 OpenAI 协议的 API 站（DeepSeek、硅基流动等），可自定义 `api-base`、`api-key`、模型
- **20 个 AI 工具**：方块放置/填充/查询/对比、NBT 读写、区域复制粘贴（支持旋转镜像）、几何体生成、区域 ASCII 平面图查看、服务器命令执行、控制台日志查看、插件文件读取、撤销等
- **多轮工具调用**：AI 自主规划 → 调用工具 → 根据结果继续，直到完成建筑
- **上下文记忆**：按玩家持久化对话历史，AI 记得之前建过什么，可延续未完成的任务（`/aibuild clear` 清除）
- **操作可撤销**：所有方块修改自动记录历史，AI 可通过 `undo` 工具自我纠错
- **交互轮数保护**：达到最大轮数后暂停并询问玩家是否继续（`/aibuild continue`），防止无限循环
- **配置热重载**：`/aibuild reload` 免重启生效

## 环境要求

- Java 17+
- Paper 1.18.2 服务端（NBT 与区域复制功能依赖该版本的 NMS，其他版本会自动降级为不可用并提示）

## 安装

1. 执行 `mvn clean package` 构建，得到 `target/AIBuilding-1.0.jar`
2. 将 jar 放入服务器的 `plugins` 文件夹，启动服务器
3. 编辑 `plugins/AIBuilding/config.yml`，填入你的 `api-key`，按需修改 `api-base` 与 `model`
4. 执行 `/aibuild reload` 或重启服务器

## 命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/aibuild <建筑描述>` | 开始 AI 建筑任务 | `aibuilding.use` |
| `/aibuild stop` | 停止当前任务 | `aibuilding.use` |
| `/aibuild continue` | 达到最大轮数后继续任务 | `aibuilding.use` |
| `/aibuild clear` | 清除自己的上下文记忆 | `aibuilding.use` |
| `/aibuild reload` | 重新加载配置（控制台可用） | `aibuilding.reload` |

权限默认仅 OP 拥有。

## AI 工具一览

| 工具 | 功能 |
|---|---|
| `place_block` | 放置单个方块，支持方块状态（如 `oak_stairs[facing=east]`） |
| `fill_blocks` | 两对角填充长方体区域 |
| `replace_blocks` | 按过滤条件批量替换区域内方块（类似 WorldEdit `//replace`） |
| `get_block` / `compare_block` | 查询方块类型 / 判断是否为指定方块 |
| `get_block_nbt` / `set_block_nbt` | 读写方块实体 NBT（SNBT 格式） |
| `copy_region` / `paste_region` | 区域复制粘贴，支持 90° 旋转与镜像 |
| `move_blocks` | 区域整体平移 |
| `generate_shape` | 生成球体/半球/圆柱/圆/金字塔/线条 |
| `get_region_summary` | 区域方块类型统计 |
| `inspect_region` | 逐层 ASCII 平面图查看区域完整布局 |
| `run_command` | 以控制台身份执行服务器命令 |
| `get_console_logs` | 查看最近控制台日志 |
| `list_files` / `read_file` | 列出/读取插件配置文件夹中的文件 |
| `set_environment` | 设置世界时间与天气 |
| `get_player_position` | 查询玩家位置与朝向 |
| `undo` | 撤销上一次方块修改 |

## 配置说明

```yaml
ai:
  api-base: "https://api.openai.com/v1"  # 兼容 OpenAI 协议的任意 API 站
  api-key: "sk-..."                       # API Key
  model: "gpt-4o-mini"                    # 模型名
  max-tokens: 4096                        # 单次回复生成上限，-1 为不限制
  max-iterations: 100                     # 任务最大交互轮数
  context-memory: true                    # 上下文记忆开关
  # ...

safety:
  allow-server-command: true              # 是否允许 AI 执行服务器命令
  allow-file-read: true                   # 是否允许 AI 读取插件文件夹
  max-fill-volume: 32768                  # 单次方块操作体积上限
  # ...

display:
  show-tool-calls: true                   # 是否显示工具调用日志
```

完整的配置项与说明见 [config.yml](src/main/resources/config.yml)。

## 常见问题

**AI 工作几轮后停止，输出为空？**
通常是输出被 `max-tokens` 截断（`finish_reason=length`），控制台会打印提示。将 `ai.max-tokens` 调大（如 8192）后 `/aibuild reload`；若使用推理模型（如 DeepSeek-R1），其思考过程也占用输出额度，建议换用普通模型。

**想换一个 API 站？**
直接修改 `ai.api-base`（如 `https://api.deepseek.com/v1`）和 `ai.model` 即可。

## 构建

```bash
mvn clean package
```

## 许可证

[LICENSE](LICENSE)
