# WMS
智能仓库管理系统

## 功能范围

- **基础资料**：仓库 → 库区 → 库位、商品分类树、商品档案、供应商、客户。
- **作业单据**：入库、出库、冻结/解冻、移库、盘点、调整，统一走 `0 草稿 → 1 待审核 → 2 已生效 / 3 已驳回`。
- **库存记账**：审核生效时按「仓库 + 库位 + 商品 + 批次」在同一事务内更新库存、写库存流水；重复审核不会重复记账。
- **查询与预警**：实时库存、库存流水、低库存、超储、临期、仓储看板。
- **AI 协作**：FastAPI 侧生成的草稿（`source=AI_AGENT`、带 `request_key` 幂等键）与人工建单共用同一套提交/审核流程与单号序列。

## Spring Boot 后端

构建和运行后端需要 JDK 21（LTS）。请将 `JAVA_HOME` 指向 JDK 21，并用 `mvn -v` 确认 Maven 使用该版本。

在后端目录运行测试和启动应用，以便读取该目录下的 `.env` 配置：

```sh
cd springboot
mvn clean test
mvn spring-boot:run
```

业务测试使用独立的 `wms_test` 库（见 `src/test/resources/application-test.yaml`），
初始化方式：`sed 's/wms_db/wms_test/g' wms.sql | mysql -h127.0.0.1 -u<user> -p`。

## Vue 前端

```sh
cd vue
npm install
npm run dev     # http://127.0.0.1:5173，/api 代理到 8080，/ai 代理到 8000
npm run build
```

菜单与路由由后端 `sys_menu` 下发，页面组件路径写在 `component` 字段
（如 `admin/order/Inbound` 对应 `vue/src/views/admin/order/Inbound.vue`）。
