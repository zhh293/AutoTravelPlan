# 高德地图智能旅游规划系统

这是一个基于Spring Boot开发的智能旅游规划系统，集成了高德地图API和AI大模型，能够为用户提供智能化的城市旅游路线规划服务。

## 功能特性

### 1. 智能景点搜索
- 基于高德地图POI接口的智能搜索建议
- 支持按城市、关键词、地点类型筛选
- Redis缓存优化，提高搜索响应速度
- 热门搜索词统计功能

### 2. AI驱动的行程规划
- 利用AI大模型进行个性化行程规划
- 实时流式返回规划结果
- 结合高德地图路径规划API优化游览路线
- 支持自定义旅游偏好设置

### 3. 可视化路线展示
- 每日行程以不同颜色区分显示
- 地图上可视化展示行程路线
- 天气预报信息集成展示

## 技术架构

### 后端技术栈
- **Spring Boot 3.5.7**: 主要框架
- **Java 17**: 开发语言
- **Spring Web MVC**: Web框架
- **Spring Data Redis**: 缓存支持
- **Apache HttpClient**: HTTP请求处理
- **Jackson**: JSON序列化/反序列化
- **OpenAI Spring Boot Starter**: AI大模型集成

### 第三方服务集成
- **高德地图开放平台**: 
  - POI搜索API
  - 路径规划API
  - 天气查询API
- **Redis**: 数据缓存
- **AI大模型服务**: 行程规划算法

## 项目结构

```
src/
├── main/
│   ├── java/
│   │   └── com.zhh.handsome.gaodemcptest/
│   │       ├── config/           # 配置类
│   │       ├── controller/       # 控制器层
│   │       ├── model/            # 数据模型
│   │       ├── service/          # 业务逻辑层
│   │       └── GaoDeMcpTestApplication.java  # 应用启动类
│   └── resources/
│       └── application.properties  # 应用配置
└── test/                         # 测试代码
```

## 核心组件详解

### 搜索服务 (SearchService)
提供基于高德地图API的地点搜索功能:
- 关键词联想搜索
- 城市范围限定
- 类型筛选（如景点、餐厅等）
- 分页支持
- Redis两级缓存机制（列表缓存+分页缓存）

### 行程规划服务 (TripPlanService)
智能行程规划的核心服务:
- 城市景点数据获取
- AI大模型交互生成行程
- 高德地图路径规划优化
- 实时SSE流式数据推送

### 数据模型
- `SuggestItem`: 搜索建议项
- `PagedResult`: 分页结果封装

## API接口文档

### 搜索接口
```
GET /search/suggest
```

参数:
- `keyword`: 搜索关键词 (必填)
- `city`: 城市名称 (可选)
- `types`: 地点类型 (可选)
- `page`: 页码，默认1 (可选)
- `size`: 每页大小，默认10，最大50 (可选)

返回示例:
```json
{
  "page": 1,
  "size": 10,
  "total": 20,
  "fromCache": false,
  "items": [
    {
      "id": "B001573BMC",
      "title": "故宫博物院",
      "titleBold": true,
      "subtitle": "北京市 东城区 景山前街4号",
      "subtitleSmall": true,
      "city": "北京市",
      "district": "东城区",
      "province": "北京市",
      "adcode": "110101",
      "address": "景山前街4号"
    }
  ]
}
```

### 智能行程规划接口
```
GET /trip/stream-plan
```

参数:
- `city`: 目标城市 (必填)
- `days`: 游玩天数，默认3，范围1-10 (可选)
- `preference`: 旅游偏好，如"历史文化"、"自然风光"等 (可选)

通过SSE流式返回结果，包括:
1. 景点信息
2. 每日路线规划
3. 天气预报

## 配置说明

在 `application.properties` 文件中配置:

```properties
# 应用名称
spring.application.name=GaoDeMCPTest

# Redis配置
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.password=
spring.data.redis.database=0

# 高德地图API Key
amap.rest.key=your_amap_key

# AI大模型配置
spring.ai.openai.api-key=your_openai_key
spring.ai.openai.base-url=https://api.xty.app

# 服务端口
server.port=8082
```

## 部署指南

### 环境要求
- Java 17+
- Maven 3.6+
- Redis 5.0+
- 高德地图开发者账号及API Key
- AI大模型服务账号及API Key

### 构建和运行

1. 克隆项目
```bash
git clone <repository-url>
cd GaoDeMCPTest
```

2. 修改配置文件
```bash
vim src/main/resources/application.properties
```

3. 编译打包
```bash
mvn clean package
```

4. 运行应用
```bash
java -jar target/GaoDeMCPTest-0.0.1-SNAPSHOT.jar
```

或者直接运行:
```bash
mvn spring-boot:run
```

## 使用示例

### 搜索景点
```bash
curl "http://localhost:8082/search/suggest?keyword=故宫&city=北京&page=1&size=5"
```

### 获取行程规划
```bash
curl "http://localhost:8082/trip/stream-plan?city=北京&days=3&preference=历史文化"
```

## 缓存策略

系统采用Redis双层缓存提升性能:
1. **列表缓存**: 缓存整个搜索结果列表，根据热度设置不同的TTL
2. **分页缓存**: 缓存具体的分页结果，减少重复计算

热点搜索词判断标准: 得分>=100为热门词，拥有更长的缓存时间。

## 错误处理

系统具有完善的错误处理机制:
- 高德API调用失败处理
- AI模型响应异常处理
- 网络异常恢复机制
- 参数校验和边界条件处理

## 性能优化

1. **异步处理**: 行程规划采用异步线程处理，避免阻塞主线程
2. **连接池**: HTTP客户端使用连接池管理，提高请求效率
3. **缓存机制**: Redis缓存减少对外部API的频繁调用
4. **流式传输**: 使用SSE实现规划结果的实时推送

## 扩展性设计

1. **模块化设计**: 各功能模块解耦，便于独立扩展
2. **配置化管理**: 关键参数通过配置文件管理，方便调整
3. **插件化AI支持**: 支持切换不同的AI大模型提供商
4. **微服务友好**: 架构设计便于后续拆分为微服务

## 注意事项

1. 需要在高德地图开放平台申请API Key并配置
2. AI大模型服务需要有效的API密钥
3. Redis服务需要正常运行
4. 确保服务器网络可以访问高德地图API和AI服务

