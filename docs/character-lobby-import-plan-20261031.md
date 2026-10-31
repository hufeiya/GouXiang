# 人物大厅与分享链接导入落地方案

调查日期：2026-10-31（中国标准时间）。本文是开发方案和一次有限样本的实测记录，不代表功能已经实现。样本为用户提供的「苏绵绵」分享链接，来源应用包名 `com.parallel.odyssey`。

## 1. 结论与边界

- **可以将公开图片与文字分离。** 该样本在未携带 Cookie 的 HTTP 请求中返回包含 JSON 的 HTML；JSON 中有完整公开简介、开场白和独立图片地址。实测图片 GET 成功，返回 WebP。页面的展示文案和图片不必从截图切割或 OCR。
- **不能据此取得完整人设。** 此样本标明 `story_setting_visible=false`，人物 `settings` 和 `style` 均为「暂未公开」。公开简介可以作为简介，不能冒充真正的私有人设；也不能从单个样本推断所有角色都一样。是否提供使用简介生成草稿，应明确标注为生成内容并由用户审核。
- **没有证据证明链接带有男性向、女性向或通用标签。** 样本 URL 中有角色 ID、语言、版本、分享渠道和分享 ID，未见表达受众性别的参数。JSON 有数值 `tag=1`、`bot_tag=0`，但含义未知，不能硬编码映射。页面单样本未发现可确认的受众字段，不等于服务端完全没有该信息。
- **分享链接解决的是已知角色详情的导入，不解决全量发现。** 用户仍需逐个提供分享链接，或另行验证并取得可用的公开目录来源；当前没有经验证的大厅列表 API、翻页参数、鉴权或规模化采集方法，不应凭空实现。
- 来源角色的文案、图片及再次分发可能涉及创作者权利和平台使用条件。正式建立共享素材库前应核实授权与来源规则；先支持用户提供链接的本地导入、记录来源和删除/撤回机制。

## 2. 证据记录及复现

### 2.1 分享网页（单样本，2026-10-31）

样本完整链接是用户给出的 `https://www.myparallelstory.com/share/chat-ai-bot/7700142542386626569?lang=zh&app_version=2310050&update_version_code=2310050&ch=copy_link&share_id=7702685536221249551`。普通未携带 Cookie 的 GET 返回 HTTP 200，HTML 中有 `<script name="pia-ssr-data" type="application/json">`。实际请求中，仅保留 `?share_id=7702685536221249551` 的同路径亦返回可解析数据；**没有证明省略 share_id 可行**。`share_id` 可能与分享者相关，应视作来源记录的一部分，不应当成角色主键或公开展示。对无参数 URL 是否有效及链接到期行为仍需专项测试。

以 `S = fetchStoryShareInfo.res.data.story` 为前缀，实际观察到：

| JSON 路径 | 观察 / 导入用途 |
| --- | --- |
| `S.story_base_data.story_id` | 字符串 `7700142542386626569`，来源角色 ID；不可转 JS Number |
| `S.story_base_data.story_name` | 角色名 |
| `S.story_base_data.introduction` | 公开完整简介，对应狗箱 `intro`，不是私设 |
| `S.story_base_data.opening_remarks.content` | 开场白，对应 `openingLine`，保留原始换行和动作描述 |
| `S.story_base_data.story_logo_url` | 方形封面，实测 GET 200、`image/webp`、800×800、36,212 字节 |
| `S.story_base_data.opening_remarks.backgroud_image_url` | 开场背景，字段原文确实拼作 `backgroud`；实测 GET 200、`image/webp`、93,560 字节 |
| `S.story_base_data.opening_remarks.avatar`、`S.story_resource.character_list[0].avatar` | 人物头像 URL；另有 `portrait`，需要根据实际展示区分用途，不能假定等同封面 |
| `S.creator_info.creator_name`、`creator_id_str` | 创作者名称及字符串 ID，用于来源归属；应优先使用字符串 ID |
| `S.story_base_data.version_id` | 观察到数字 7，可保存原始版本用于检查更新；其完整语义未验证 |
| `S.story_base_data.story_setting_visible`、`S.story_resource.character_list[0].settings`、`style` | 本样本分别为 `false`、「暂未公开」、「暂未公开」，不能写入 profile 当作设定 |
| `S.story_base_data.tag`、`S.story_resource.character_list[0].bot_tag` | 本样本分别为 1、0；语义未知，保留原值，不作为受众筛选 |

对白名单之外的整个 SSR JSON 递归搜索字段名 `gender`、`sex`、`audience`、`orient`、`prefer`、`target`、`tag`，只发现空的 `node_target` 和上述数字标签；这是该次字段名搜索的范围，不能证明任意命名的标签不存在。该 JSON 还含 `share_user_detail`；不要把整份 HTML 或 JSON 入库、提交到仓库或放日志。图片 URL 为带签名 URL，可能过期，应在允许使用的前提下下载到本地，并保留来源而非依赖长期热链。图片与文本可能更新，下载时应重新校验。

最小复现实验（只输出状态、顶层键，不打印分享者信息或图片签名）：

```python
import json
import requests
from bs4 import BeautifulSoup

url = 'https://www.myparallelstory.com/share/chat-ai-bot/7700142542386626569?share_id=7702685536221249551'
r = requests.get(url, timeout=20)
r.raise_for_status()
soup = BeautifulSoup(r.text, 'html.parser')
script = soup.find('script', attrs={'name': 'pia-ssr-data', 'type': 'application/json'})
assert script and script.string
payload = json.loads(script.string)
s = payload['fetchStoryShareInfo']['res']['data']['story']
b = s['story_base_data']
print(b['story_id'], b['story_name'], bool(b['introduction']),
      bool(b['opening_remarks']['content']), b['story_setting_visible'])
```

解析前还需检查响应大小、内容类型、错误/登录页面、JSON 业务状态、字段类型及缺失情况。HTTP 200 本身不代表内容可用。链接、外部文本和返回 URL 都是不可信输入；只允许预期 HTTPS 站点及经过校验的图片域名，限制重定向、内网地址、图片体积/像素和格式，防止 SSRF、磁盘耗尽及恶意文件。实际允许域名应由验证过的配置决定，失败时安全报错，不盲目跟随未知重定向。

### 2.2 真机观察与第二个样本

2026-10-31，ADB 连接到 Redmi K20 Pro（Android 11），猫箱 `com.parallel.odyssey` 版本 2.31.0、versionCode 2310050。实际打开搜索大厅的「情感陪伴」分类：Android UI 层次可读到卡片完整简介、作者、浏览量，即使画面有省略；图片是 ImageView，界面层文本读取本身不提供图片文件。进入「俞菲」详情，展开介绍可读取完整文本，开场白是独立文本；点击分享 → 复制链接成功，路径结构和用户样本一致，链接参数未见受众字段。操作覆盖了手机剪贴板。没有修改账号性别，不能从此次操作证明当前设置如何影响列表。

对刚复制的「俞菲」链接做未携带 Cookie 的 GET：HTTP 200，存在相同 SSR script，ID `7672390768138829830`，可读简介和开场；`story_setting_visible=false`，`settings`、`style` 为「暂未公开」。相同关键词范围内未发现明确的受众字段；`story_base_data.tag=3`、人物 `bot_tag=0`。与「苏绵绵」的 `tag=1` 不同，但这两个样本并不足以解释数字含义。两个样本均不能证明所有分享页面都具备同样结构或均隐藏设定。

用户提供的两张截图分别显示双列分类大厅和人物详情。截图只能说明该次界面显示了这些人物，不能证明列表受性别筛选或给每个人物赋予何种受众。

## 3. 数据结构与受众语义

**受众（面向哪类用户）和角色本身的性别是两件事。** 用户首次选择男性或女性用于匹配受众，不能据人物外观、名字、代词或用户当前选择推断受众。分类如「情感陪伴」也不是受众标签。

建议独立本地目录表 `catalog_characters`，与已加入聊天的 `characters` 分离。现有 `GouXiangRepo.homeFeed` 从 `characters` 的 `observeAll()` 读取，批量塞入会污染首页及其他角色工作流。建议字段：

| 字段 | 用途 |
| --- | --- |
| `id`、`source`、`sourceId` | 本地主键；`(source, sourceId)` 唯一索引；来源 ID 统一文本保存 |
| `name`、`intro`、`openingLine` | 展示字段；原始值与清洗版本分开时须可追溯 |
| `profile`、`profileStatus` | 公开真实设定才标记 `source_public`；没有时为 `missing`；用户编辑或生成草稿分别标记，不能把「暂未公开」作为真实设定 |
| `coverFile`、`avatarFile`、`backgroundFile` | 区分素材用途、保存本地相对文件名及哈希；外部签名 URL 不作为持久文件路径 |
| `creatorId`、`creatorName`、`sourceVersion` | 创作者归属及版本；采用可空字段，缺失不是空内容的证明 |
| `audience` | `male`（男性向）、`female`（女性向）、`all`（明确通用/无受众限制）、`unknown`（未证实）。如果来源允许多选，改为关联表或集合，不要用单枚举丢失双向信息 |
| `audienceRaw`、`audienceEvidence`、`audienceOrigin`、`audienceUpdatedAt` | 只保留与标签直接相关的原始值/来源及审定记录；origin 区分来源明确字段、人工确认、未知。未经确认的数字不映射 |
| `characterGender` | 可空，独立于受众；不以角色性别推断受众 |
| `sourceUrl`、`fetchedAt`、`importStatus`、`rightsStatus` | 来源、抓取时间、状态及授权/撤回记录。分享 ID 和分享人相关信息尽量单独限制访问；不得记录 Cookie |
| `category` / 分类关联表 | 公开分类及其证据；未知分类留空，不作性别标签 |

受众筛选的初始产品规则：男性选择显示 `male` 和经确认的 `all`；女性选择显示 `female` 和经确认的 `all`；`unknown` 默认不进入性别定向大厅。未知条目进入待审核/手动标注入口，否则本样本及大量无明确标签的内容会不可见；不得把 `unknown` 偷换成 `all`。本地已加入的角色和聊天历史不因更改选择而删除或隐藏。偏好可在设置修改；首次选择、偏好与身份表述是产品设计，数据层仍需保留未选择状态。人工作出的受众标签应能修改，来源更新不得覆盖人工确认，冲突留审核记录。

## 4. 导入管线

1. **收集**：粘贴整段分享文案，提取其中 HTTPS URL；验证主机、路径和数字字符串 ID，记录分享链接但不要按文本名称去重。只使用用户提供或有权使用的来源；先建立单条导入，再研究目录来源。
2. **获取**：使用有限超时、响应大小上限与有节制的请求；支持网络错误、限流、失效、登录要求和页面结构变化。不要绕过登录、访问控制或风控；不存取用户 Cookie。按可用链接原样请求，暂不假设参数可以删除。
3. **解析**：只读取上述 SSR script 和可验证的白名单字段，检查业务状态与类型；区分字段缺失、空字符串、「暂未公开」和已公开。多角色或多开场要保存对应关系，不要盲选首项。对未识别结构返回可诊断错误而非成功空卡；不要执行 HTML 中的脚本。
4. **素材**：将可用封面、头像和背景按用途暂存，核对 HTTPS 重定向目标、MIME 与真实解码格式、字节数和像素，再计算哈希、原子重命名到应用私有目录。分别标识下载成功/失败/无图；失败允许重试和占位。避免目录穿越、同名覆盖。若数据库事务失败，清理孤儿临时文件；后台清理需防止删除仍被引用的素材。
5. **预览和确认**：展示名称、简介、开场、来源、图片、已公开/缺失的人设及受众证据。用户可补充 profile、修正受众；保留原始值和用户覆盖值。不要求先选择可疑的受众才能入目录，但未知不进入定向推荐。
6. **幂等入库**：以 `(source, sourceId)` upsert 目录记录，更新来源快照时保留用户修改、聊天副本和人工受众；同源 ID 并发导入只能有一条。失败不留半条可见卡。不可用名字去重：同名不同 ID 必须并存。来源已撤回时标记不可用并按授权策略处理本地素材，不静默删除用户会话。
7. **加入聊天**：从目录快照创建独立 `CharacterEntity` 和会话，真实 profile 缺失时提示补全或明确选择经审核的生成草稿。复用现有会话初始化语义，但要先解决 `SeedDao.saveCharacterAtomic` 按名字拒绝重名的问题。建立 `catalogId → characterId` 唯一关联并在事务内防止重复加入；已有会话则打开而非重复插首条消息。目录更新不自动覆盖用户已编辑的人设、消息或记忆。

## 5. 界面与现有项目接线

- `ui/home/HomeFeedScreen.kt` 顶部搜索图标目前没有点击事件：增加进入大厅回调；在 `MainActivity.kt` 当前底栏及全屏覆盖层模式中接入大厅、详情及系统返回，保留底栏/原有状态。
- 新建大厅页面：搜索框、分类横向滚动、双列图片卡、标题/简介/作者和必要的加载、空、离线、错误状态；分类、搜索和受众筛选在数据层组合，使用稳定排序（如 `fetchedAt DESC, id DESC`）和分页，避免分页时重复/漏项。若数据量增长再评估 Room Paging/索引及 FTS。
- 详情展示本地图片、公开简介、开场、来源、受众状态和「开始聊天」；缺失 profile 要清楚显示，不让用户误以为已取得完整人设。
- `ui/discover/DiscoverScreen.kt` 已是本地角色搜索/历史，不是外部人物大厅；保持其语义或明确区分入口。
- `CharacterEntity.kt` 中已有 `profile`、`intro`、`exampleLines`、`openingLine`、`appearance`、`avatarFile`、`source`；头像约定在 `filesDir/avatars/` 中存文件名，导入素材接入时须遵守文件读取方式而非写入远程 URL。
- Room 当前版本 13、`exportSchema=false`。新增目录及关联表需定义并注册 13→14 迁移，按实际实体校验 SQL 与默认值；不得使用破坏性迁移。首次性别偏好存独立设置，已有安装迁移为未选择，不改变已有会话。
- 编辑风险：`MainViewModel.saveCharacter` 经 `CharacterDraft.toEntity` 重建整行，DAO 编辑时整体更新，可能重置未在表单携带的 `avatarFile`、`source`、角色权限等；实现导入前须改为在 existing 上只更新表单字段，或将来源等放独立表，并给存量字段加回归测试。

## 6. 待验证及验收

**最先验证的阻塞项**：多取不同创作者、公开/未公开人设、不同分类和受众的少量样本；记录是否存在明确受众字段及字段语义。若比较男性、女性账号大厅，需使用有权限的两个账号或经用户同意切换，并记录时间、分类、排序及分页；列表重叠/差异只能证明观察到的推荐差异，不能独立证明单个人物受众。保持单样本结论的边界。另验证失效链接、`share_id` 必要性、图片签名过期、访问频率限制、源站规则和授权；无法验证时保留未知。

验收测试：

- 样本链接导入后，简介和开场与页面一致，图片可独立展示；隐藏人设显示缺失，受众为 `unknown`。链接不可用、格式改变或图片失败均有明确状态，不产生半成品。
- 同链接重复或并发导入只产生一条目录记录；不同 ID 的同名角色共存；重复开始聊天打开原会话，不重复写开场。超长 ID 完整保留。
- `male`/`female`/`all`/`unknown` 四类在两种选择下符合第 3 节规则；人工纠正可持久化，来源更新不覆盖，角色自身性别变化不改变受众。切换偏好不删除既有聊天。
- 升级既有 v13 数据库后角色、会话、消息、记忆、图片及权限不丢；编辑已导入角色不重置来源、头像、权限；重启后图片可用。包括 `DatabaseMigrationTest.kt` 的升级验证及相关 DAO/事务测试。
- 检查 URL 重定向至内网、超大响应、伪装图片、损坏图片、HTML 错误页、可空字段、下载中断和磁盘写入失败；日志与数据库不含 Cookie、分享者完整页面或无关个人信息。

实施顺序：① 单链接解析与预览原型（含标签未知）→ ② 独立目录、媒体管理和迁移 → ③ 首次偏好与大厅/详情 → ④ 安全、幂等的加入聊天 → ⑤ 小样本真实标签与其他目录来源的验证。目录来源未验证前不承诺自动大规模扩库。
