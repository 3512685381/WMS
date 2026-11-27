-- 智能仓储 WMS + 多 AI Agent 初始化脚本
-- 支持 MySQL 8.0.19+ / 8.4，使用 InnoDB 和 utf8mb4。
-- 标准导入
-- mysql --default-character-set=utf8mb4 -u root -p < wms.sql
-- 仅建表
-- mysql --default-character-set=utf8mb4 --init-command="SET @wms_seed_demo = 0" -u root -p < wms.sql
-- 演示账号 admin、operator、user 共用开发密码 WmsDemo@2026，库内存储独立 BCrypt 哈希。
-- 本脚本用于初始化。演示数据在空库中以同一事务导入，重复导入保留现有数据。
-- 业务日期按 Asia/Shanghai 计算，默认数量精度为三位小数。

SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS wms_db
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;
USE wms_db;

-- 一、用户、角色、菜单与 JWT

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码散列',
    nickname VARCHAR(64) NOT NULL,
    phone VARCHAR(32) NULL,
    status TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '0 禁用，1 启用',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username),
    CONSTRAINT ck_sys_user_status CHECK (status IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户';

CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT NOT NULL AUTO_INCREMENT,
    role_code VARCHAR(32) NOT NULL,
    role_name VARCHAR(64) NOT NULL,
    status TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '0 禁用，1 启用',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_code (role_code),
    CONSTRAINT ck_sys_role_status CHECK (status IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色';

CREATE TABLE IF NOT EXISTS sys_menu (
    id BIGINT NOT NULL AUTO_INCREMENT,
    parent_id BIGINT NULL COMMENT 'NULL 为根节点',
    menu_name VARCHAR(64) NOT NULL,
    path VARCHAR(160) NULL,
    component VARCHAR(160) NULL COMMENT '相对于 vue/src/views 的组件路径',
    icon VARCHAR(64) NULL,
    menu_type CHAR(1) NOT NULL COMMENT 'M 目录，C 菜单，F 按钮',
    perms VARCHAR(96) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_menu_path (path),
    KEY idx_sys_menu_tree (parent_id, is_deleted, sort_order),
    CONSTRAINT fk_sys_menu_parent FOREIGN KEY (parent_id) REFERENCES sys_menu (id),
    CONSTRAINT ck_sys_menu_type CHECK (menu_type IN ('M', 'C', 'F')),
    CONSTRAINT ck_sys_menu_route CHECK (
        (menu_type = 'M' AND path IS NOT NULL)
        OR (menu_type = 'C' AND path IS NOT NULL AND component IS NOT NULL)
        OR (menu_type = 'F' AND path IS NULL AND component IS NULL AND perms IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='动态路由和按钮权限';

CREATE TABLE IF NOT EXISTS sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (user_id, role_id),
    KEY idx_sys_user_role_role (role_id, user_id),
    CONSTRAINT fk_sys_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_sys_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色关联';

CREATE TABLE IF NOT EXISTS sys_role_menu (
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (role_id, menu_id),
    KEY idx_sys_role_menu_menu (menu_id, role_id),
    CONSTRAINT fk_sys_role_menu_role FOREIGN KEY (role_id) REFERENCES sys_role (id) ON DELETE CASCADE,
    CONSTRAINT fk_sys_role_menu_menu FOREIGN KEY (menu_id) REFERENCES sys_menu (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色菜单关联';

CREATE TABLE IF NOT EXISTS sys_token_blacklist (
    jti CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expire_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (jti),
    KEY idx_sys_token_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='已退出 JWT 黑名单';

-- 二、仓储基础资料

CREATE TABLE IF NOT EXISTS wms_warehouse (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    address VARCHAR(255) NULL,
    manager VARCHAR(64) NULL,
    status TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '0 停用，1 启用',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    UNIQUE KEY uk_warehouse_code (code),
    CONSTRAINT ck_warehouse_status CHECK (status IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='仓库';

CREATE TABLE IF NOT EXISTS wms_area (
    id BIGINT NOT NULL AUTO_INCREMENT,
    warehouse_id BIGINT NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    area_type VARCHAR(16) NOT NULL COMMENT 'RECEIVING 收货，STORAGE 存储，PICKING 拣货，DEFECTIVE 不良品',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    UNIQUE KEY uk_area_code (warehouse_id, code),
    UNIQUE KEY uk_area_warehouse_id (warehouse_id, id),
    CONSTRAINT fk_area_warehouse FOREIGN KEY (warehouse_id) REFERENCES wms_warehouse (id),
    CONSTRAINT ck_area_type CHECK (area_type IN ('RECEIVING', 'STORAGE', 'PICKING', 'DEFECTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库区';

CREATE TABLE IF NOT EXISTS wms_location (
    id BIGINT NOT NULL AUTO_INCREMENT,
    warehouse_id BIGINT NOT NULL,
    area_id BIGINT NOT NULL,
    code VARCHAR(32) NOT NULL,
    max_capacity DECIMAL(18, 3) NOT NULL DEFAULT 0 COMMENT '0 表示未设置容量上限',
    status TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '0 停用，1 启用',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    UNIQUE KEY uk_location_code (warehouse_id, code),
    UNIQUE KEY uk_location_warehouse_id (warehouse_id, id),
    KEY idx_location_area_status (warehouse_id, area_id, status, is_deleted),
    CONSTRAINT fk_location_area FOREIGN KEY (warehouse_id, area_id) REFERENCES wms_area (warehouse_id, id),
    CONSTRAINT ck_location_capacity CHECK (max_capacity >= 0),
    CONSTRAINT ck_location_status CHECK (status IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库位';

CREATE TABLE IF NOT EXISTS wms_category (
    id BIGINT NOT NULL AUTO_INCREMENT,
    parent_id BIGINT NULL COMMENT 'NULL 为根分类',
    name VARCHAR(128) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    KEY idx_category_tree (parent_id, is_deleted, sort_order),
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES wms_category (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品分类';

CREATE TABLE IF NOT EXISTS wms_supplier (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    contact VARCHAR(64) NULL,
    phone VARCHAR(32) NULL,
    address VARCHAR(255) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    UNIQUE KEY uk_supplier_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商';

CREATE TABLE IF NOT EXISTS wms_customer (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    contact VARCHAR(64) NULL,
    phone VARCHAR(32) NULL,
    address VARCHAR(255) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户';

CREATE TABLE IF NOT EXISTS wms_product (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sku_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    category_id BIGINT NULL,
    spec VARCHAR(128) NULL,
    unit VARCHAR(16) NOT NULL,
    batch_managed TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '0 使用 NO_BATCH，1 按批次管理',
    min_stock DECIMAL(18, 3) NOT NULL DEFAULT 0,
    max_stock DECIMAL(18, 3) NOT NULL DEFAULT 0 COMMENT '0 表示未设置超储上限',
    shelf_life_days INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 表示无保质期设置',
    default_supplier_id BIGINT NULL,
    reference_price DECIMAL(18, 4) NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    UNIQUE KEY uk_product_sku (sku_code),
    KEY idx_product_category (category_id, is_deleted),
    KEY idx_product_name (name),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES wms_category (id),
    CONSTRAINT fk_product_supplier FOREIGN KEY (default_supplier_id) REFERENCES wms_supplier (id),
    CONSTRAINT ck_product_batch_managed CHECK (batch_managed IN (0, 1)),
    CONSTRAINT ck_product_stock CHECK (min_stock >= 0 AND (max_stock = 0 OR max_stock >= min_stock)),
    CONSTRAINT ck_product_price CHECK (reference_price >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品档案';

-- 三、库存余额与记账流水
-- 同张单据在同一库存维度的同类分录先合并，再写入一条流水。

CREATE TABLE IF NOT EXISTS wms_inventory (
    id BIGINT NOT NULL AUTO_INCREMENT,
    warehouse_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    batch_no VARCHAR(64) NOT NULL DEFAULT 'NO_BATCH',
    production_date DATE NULL,
    expire_date DATE NULL,
    quantity DECIMAL(18, 3) NOT NULL DEFAULT 0,
    frozen_quantity DECIMAL(18, 3) NOT NULL DEFAULT 0,
    available_quantity DECIMAL(18, 3) GENERATED ALWAYS AS (quantity - frozen_quantity) VIRTUAL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    UNIQUE KEY uk_inventory_dimension (warehouse_id, location_id, product_id, batch_no),
    UNIQUE KEY uk_inventory_log_reference (id, warehouse_id, location_id, product_id, batch_no),
    KEY idx_inventory_product_warehouse (product_id, warehouse_id, is_deleted),
    KEY idx_inventory_expiry (is_deleted, expire_date),
    CONSTRAINT fk_inventory_location FOREIGN KEY (warehouse_id, location_id) REFERENCES wms_location (warehouse_id, id),
    CONSTRAINT fk_inventory_product FOREIGN KEY (product_id) REFERENCES wms_product (id),
    CONSTRAINT ck_inventory_batch CHECK (CHAR_LENGTH(TRIM(batch_no)) > 0),
    CONSTRAINT ck_inventory_qty CHECK (quantity >= 0 AND frozen_quantity BETWEEN 0 AND quantity),
    CONSTRAINT ck_inventory_dates CHECK (expire_date IS NULL OR production_date IS NULL OR expire_date >= production_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='实时库存余额';

CREATE TABLE IF NOT EXISTS wms_inventory_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    inventory_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    batch_no VARCHAR(64) NOT NULL DEFAULT 'NO_BATCH',
    biz_type VARCHAR(16) NOT NULL,
    order_no CHAR(14) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    before_qty DECIMAL(18, 3) NOT NULL,
    change_qty DECIMAL(18, 3) NOT NULL,
    after_qty DECIMAL(18, 3) NOT NULL,
    before_frozen_qty DECIMAL(18, 3) NOT NULL,
    change_frozen_qty DECIMAL(18, 3) NOT NULL,
    after_frozen_qty DECIMAL(18, 3) NOT NULL,
    operator_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    UNIQUE KEY uk_inventory_log_posting (order_no, inventory_id, biz_type),
    KEY idx_inventory_log_timeline (inventory_id, created_at),
    KEY idx_inventory_log_warehouse_time (warehouse_id, created_at),
    KEY idx_inventory_log_biz_time (biz_type, created_at),
    CONSTRAINT fk_inventory_log_inventory FOREIGN KEY (inventory_id, warehouse_id, location_id, product_id, batch_no)
        REFERENCES wms_inventory (id, warehouse_id, location_id, product_id, batch_no),
    CONSTRAINT fk_inventory_log_operator FOREIGN KEY (operator_id) REFERENCES sys_user (id),
    CONSTRAINT ck_inventory_log_qty CHECK (before_qty >= 0 AND after_qty >= 0 AND before_qty + change_qty = after_qty),
    CONSTRAINT ck_inventory_log_frozen CHECK (
        before_frozen_qty BETWEEN 0 AND before_qty AND after_frozen_qty BETWEEN 0 AND after_qty
        AND before_frozen_qty + change_frozen_qty = after_frozen_qty
    ),
    CONSTRAINT ck_inventory_log_change CHECK (
        (biz_type = 'FREEZE' AND change_qty = 0 AND change_frozen_qty > 0)
        OR (biz_type = 'UNFREEZE' AND change_qty = 0 AND change_frozen_qty < 0)
        OR (biz_type IN ('INBOUND', 'TRANSFER_IN') AND change_qty > 0 AND change_frozen_qty = 0)
        OR (biz_type IN ('OUTBOUND', 'TRANSFER_OUT') AND change_qty < 0 AND change_frozen_qty = 0)
        OR (biz_type = 'ADJUST' AND change_qty <> 0 AND change_frozen_qty = 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存和冻结量变动流水';

-- 四、编号序列与六类作业单据
-- Java 在单据事务内锁定编号序列行。人工操作和 AI 代建共用该序列。

CREATE TABLE IF NOT EXISTS wms_order_sequence (
    order_prefix CHAR(2) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    biz_date DATE NOT NULL COMMENT 'Asia/Shanghai 业务日期',
    last_no SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (order_prefix, biz_date),
    CONSTRAINT ck_order_sequence_prefix CHECK (order_prefix IN ('RK', 'CK', 'YK', 'TZ', 'PD', 'DJ')),
    CONSTRAINT ck_order_sequence_limit CHECK (last_no <= 9999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='按前缀和日期分配单号序列';

CREATE TABLE IF NOT EXISTS wms_inbound_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no CHAR(14) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    warehouse_id BIGINT NOT NULL,
    supplier_id BIGINT NULL,
    source VARCHAR(8) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL 人工，AI_AGENT 代建',
    request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '创建人的草稿请求幂等键',
    status TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 草稿，1 待审核，2 已生效，3 已驳回',
    remark VARCHAR(500) NULL,
    submitted_at DATETIME(3) NULL,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME(3) NULL,
    reject_reason VARCHAR(500) NULL,
    posted_at DATETIME(3) NULL COMMENT '审核生效或盘点完结时间',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    UNIQUE KEY uk_inbound_order_no (order_no),
    UNIQUE KEY uk_inbound_order_warehouse (id, warehouse_id),
    KEY idx_inbound_order_status (status, is_deleted, created_at),
    KEY idx_inbound_order_warehouse (warehouse_id, is_deleted, created_at),
    KEY idx_inbound_order_posted (posted_at),
    CONSTRAINT fk_inbound_warehouse FOREIGN KEY (warehouse_id) REFERENCES wms_warehouse (id),
    CONSTRAINT fk_inbound_reviewer FOREIGN KEY (reviewed_by) REFERENCES sys_user (id),
    CONSTRAINT fk_inbound_supplier FOREIGN KEY (supplier_id) REFERENCES wms_supplier (id),
    UNIQUE KEY uk_inbound_request_key (created_by, request_key),
    KEY idx_inbound_order_source (source, status, is_deleted),
    CONSTRAINT ck_inbound_order_source CHECK (source IN ('MANUAL', 'AI_AGENT')),
    CONSTRAINT ck_inbound_order_request CHECK (
        (request_key IS NULL AND source = 'MANUAL')
        OR (created_by IS NOT NULL AND CHAR_LENGTH(TRIM(request_key)) > 0 AND request_key IS NOT NULL)
    ),
    CONSTRAINT ck_inbound_order_status CHECK (status IN (0, 1, 2, 3)),
    CONSTRAINT ck_inbound_order_no CHECK (REGEXP_LIKE(order_no, '^RK[0-9]{12}$', 'c'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='入库单';

CREATE TABLE IF NOT EXISTS wms_inbound_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    batch_no VARCHAR(64) NOT NULL DEFAULT 'NO_BATCH',
    production_date DATE NULL,
    expire_date DATE NULL,
    quantity DECIMAL(18, 3) NOT NULL,
    unit_price DECIMAL(18, 4) NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    KEY idx_inbound_item_order (order_id, is_deleted),
    CONSTRAINT fk_inbound_item_order FOREIGN KEY (order_id, warehouse_id)
        REFERENCES wms_inbound_order (id, warehouse_id),
    CONSTRAINT fk_inbound_item_product FOREIGN KEY (product_id) REFERENCES wms_product (id),
    CONSTRAINT fk_inbound_item_location FOREIGN KEY (warehouse_id, location_id) REFERENCES wms_location (warehouse_id, id),
    CONSTRAINT ck_inbound_item_batch CHECK (CHAR_LENGTH(TRIM(batch_no)) > 0),
    CONSTRAINT ck_inbound_item_qty CHECK (quantity > 0 AND unit_price >= 0),
    CONSTRAINT ck_inbound_item_dates CHECK (expire_date IS NULL OR production_date IS NULL OR expire_date >= production_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='入库单明细';

CREATE TABLE IF NOT EXISTS wms_outbound_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no CHAR(14) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    warehouse_id BIGINT NOT NULL,
    customer_id BIGINT NULL,
    source VARCHAR(8) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL 人工，AI_AGENT 代建',
    request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '创建人的草稿请求幂等键',
    status TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 草稿，1 待审核，2 已生效，3 已驳回',
    remark VARCHAR(500) NULL,
    submitted_at DATETIME(3) NULL,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME(3) NULL,
    reject_reason VARCHAR(500) NULL,
    posted_at DATETIME(3) NULL COMMENT '审核生效或盘点完结时间',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    UNIQUE KEY uk_outbound_order_no (order_no),
    UNIQUE KEY uk_outbound_order_warehouse (id, warehouse_id),
    KEY idx_outbound_order_status (status, is_deleted, created_at),
    KEY idx_outbound_order_warehouse (warehouse_id, is_deleted, created_at),
    KEY idx_outbound_order_posted (posted_at),
    CONSTRAINT fk_outbound_warehouse FOREIGN KEY (warehouse_id) REFERENCES wms_warehouse (id),
    CONSTRAINT fk_outbound_reviewer FOREIGN KEY (reviewed_by) REFERENCES sys_user (id),
    CONSTRAINT fk_outbound_customer FOREIGN KEY (customer_id) REFERENCES wms_customer (id),
    UNIQUE KEY uk_outbound_request_key (created_by, request_key),
    KEY idx_outbound_order_source (source, status, is_deleted),
    CONSTRAINT ck_outbound_order_source CHECK (source IN ('MANUAL', 'AI_AGENT')),
    CONSTRAINT ck_outbound_order_request CHECK (
        (request_key IS NULL AND source = 'MANUAL')
        OR (created_by IS NOT NULL AND CHAR_LENGTH(TRIM(request_key)) > 0 AND request_key IS NOT NULL)
    ),
    CONSTRAINT ck_outbound_order_status CHECK (status IN (0, 1, 2, 3)),
    CONSTRAINT ck_outbound_order_no CHECK (REGEXP_LIKE(order_no, '^CK[0-9]{12}$', 'c'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='出库单';

CREATE TABLE IF NOT EXISTS wms_outbound_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    batch_no VARCHAR(64) NOT NULL DEFAULT 'NO_BATCH',
    quantity DECIMAL(18, 3) NOT NULL,
    unit_price DECIMAL(18, 4) NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    KEY idx_outbound_item_order (order_id, is_deleted),
    CONSTRAINT fk_outbound_item_order FOREIGN KEY (order_id, warehouse_id)
        REFERENCES wms_outbound_order (id, warehouse_id),
    CONSTRAINT fk_outbound_item_product FOREIGN KEY (product_id) REFERENCES wms_product (id),
    CONSTRAINT fk_outbound_item_location FOREIGN KEY (warehouse_id, location_id) REFERENCES wms_location (warehouse_id, id),
    CONSTRAINT ck_outbound_item_batch CHECK (CHAR_LENGTH(TRIM(batch_no)) > 0),
    CONSTRAINT ck_outbound_item_qty CHECK (quantity > 0 AND unit_price >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='出库单明细';

CREATE TABLE IF NOT EXISTS wms_transfer_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no CHAR(14) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_warehouse_id BIGINT NOT NULL,
    to_warehouse_id BIGINT NOT NULL,
    status TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 草稿，1 待审核，2 已生效，3 已驳回',
    remark VARCHAR(500) NULL,
    submitted_at DATETIME(3) NULL,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME(3) NULL,
    reject_reason VARCHAR(500) NULL,
    posted_at DATETIME(3) NULL COMMENT '审核生效或盘点完结时间',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    UNIQUE KEY uk_transfer_order_no (order_no),
    UNIQUE KEY uk_transfer_order_warehouse (id, from_warehouse_id, to_warehouse_id),
    KEY idx_transfer_order_status (status, is_deleted, created_at),
    KEY idx_transfer_order_warehouse (from_warehouse_id, is_deleted, created_at),
    KEY idx_transfer_order_posted (posted_at),
    CONSTRAINT fk_transfer_from_warehouse FOREIGN KEY (from_warehouse_id) REFERENCES wms_warehouse (id),
    CONSTRAINT fk_transfer_to_warehouse FOREIGN KEY (to_warehouse_id) REFERENCES wms_warehouse (id),
    CONSTRAINT fk_transfer_reviewer FOREIGN KEY (reviewed_by) REFERENCES sys_user (id),
    CONSTRAINT ck_transfer_order_status CHECK (status IN (0, 1, 2, 3)),
    CONSTRAINT ck_transfer_order_no CHECK (REGEXP_LIKE(order_no, '^YK[0-9]{12}$', 'c'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='移库单';

CREATE TABLE IF NOT EXISTS wms_transfer_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    from_warehouse_id BIGINT NOT NULL,
    to_warehouse_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    from_location_id BIGINT NOT NULL,
    to_location_id BIGINT NOT NULL,
    batch_no VARCHAR(64) NOT NULL DEFAULT 'NO_BATCH',
    quantity DECIMAL(18, 3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    KEY idx_transfer_item_order (order_id, is_deleted),
    CONSTRAINT fk_transfer_item_order FOREIGN KEY (order_id, from_warehouse_id, to_warehouse_id)
        REFERENCES wms_transfer_order (id, from_warehouse_id, to_warehouse_id),
    CONSTRAINT fk_transfer_item_product FOREIGN KEY (product_id) REFERENCES wms_product (id),
    CONSTRAINT fk_transfer_item_from_location FOREIGN KEY (from_warehouse_id, from_location_id) REFERENCES wms_location (warehouse_id, id),
    CONSTRAINT fk_transfer_item_to_location FOREIGN KEY (to_warehouse_id, to_location_id) REFERENCES wms_location (warehouse_id, id),
    CONSTRAINT ck_transfer_item_batch CHECK (CHAR_LENGTH(TRIM(batch_no)) > 0),
    CONSTRAINT ck_transfer_item_qty CHECK (quantity > 0),
    CONSTRAINT ck_transfer_item_locations CHECK (from_location_id <> to_location_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='移库单明细';

CREATE TABLE IF NOT EXISTS wms_stocktake_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no CHAR(14) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    warehouse_id BIGINT NOT NULL,
    status TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 草稿，1 待审核，2 已生效，3 已驳回',
    remark VARCHAR(500) NULL,
    submitted_at DATETIME(3) NULL,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME(3) NULL,
    reject_reason VARCHAR(500) NULL,
    posted_at DATETIME(3) NULL COMMENT '审核生效或盘点完结时间',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    UNIQUE KEY uk_stocktake_order_no (order_no),
    UNIQUE KEY uk_stocktake_order_warehouse (id, warehouse_id),
    KEY idx_stocktake_order_status (status, is_deleted, created_at),
    KEY idx_stocktake_order_warehouse (warehouse_id, is_deleted, created_at),
    KEY idx_stocktake_order_posted (posted_at),
    CONSTRAINT fk_stocktake_warehouse FOREIGN KEY (warehouse_id) REFERENCES wms_warehouse (id),
    CONSTRAINT fk_stocktake_reviewer FOREIGN KEY (reviewed_by) REFERENCES sys_user (id),
    CONSTRAINT ck_stocktake_order_status CHECK (status IN (0, 1, 2, 3)),
    CONSTRAINT ck_stocktake_order_no CHECK (REGEXP_LIKE(order_no, '^PD[0-9]{12}$', 'c'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='盘点单';

CREATE TABLE IF NOT EXISTS wms_stocktake_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    batch_no VARCHAR(64) NOT NULL DEFAULT 'NO_BATCH',
    book_qty DECIMAL(18, 3) NOT NULL,
    actual_qty DECIMAL(18, 3) NULL COMMENT 'NULL 表示尚未录入',
    diff_qty DECIMAL(18, 3) GENERATED ALWAYS AS (actual_qty - book_qty) VIRTUAL,
    inventory_version BIGINT UNSIGNED NULL COMMENT '盘点生成时的库存版本，NULL 表示尚未建账',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    KEY idx_stocktake_item_order (order_id, is_deleted),
    CONSTRAINT fk_stocktake_item_order FOREIGN KEY (order_id, warehouse_id)
        REFERENCES wms_stocktake_order (id, warehouse_id),
    CONSTRAINT fk_stocktake_item_product FOREIGN KEY (product_id) REFERENCES wms_product (id),
    CONSTRAINT fk_stocktake_item_location FOREIGN KEY (warehouse_id, location_id) REFERENCES wms_location (warehouse_id, id),
    CONSTRAINT ck_stocktake_item_batch CHECK (CHAR_LENGTH(TRIM(batch_no)) > 0),
    UNIQUE KEY uk_stocktake_item_dimension (order_id, location_id, product_id, batch_no),
    CONSTRAINT ck_stocktake_item_qty CHECK (book_qty >= 0 AND (actual_qty IS NULL OR actual_qty >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='盘点单明细';

CREATE TABLE IF NOT EXISTS wms_adjust_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no CHAR(14) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    warehouse_id BIGINT NOT NULL,
    stocktake_order_id BIGINT NULL COMMENT '产生差异的盘点单',
    status TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 草稿，1 待审核，2 已生效，3 已驳回',
    remark VARCHAR(500) NULL,
    submitted_at DATETIME(3) NULL,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME(3) NULL,
    reject_reason VARCHAR(500) NULL,
    posted_at DATETIME(3) NULL COMMENT '审核生效或盘点完结时间',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    UNIQUE KEY uk_adjust_order_no (order_no),
    UNIQUE KEY uk_adjust_order_warehouse (id, warehouse_id),
    KEY idx_adjust_order_status (status, is_deleted, created_at),
    KEY idx_adjust_order_warehouse (warehouse_id, is_deleted, created_at),
    KEY idx_adjust_order_posted (posted_at),
    CONSTRAINT fk_adjust_warehouse FOREIGN KEY (warehouse_id) REFERENCES wms_warehouse (id),
    CONSTRAINT fk_adjust_reviewer FOREIGN KEY (reviewed_by) REFERENCES sys_user (id),
    UNIQUE KEY uk_adjust_stocktake (stocktake_order_id),
    CONSTRAINT fk_adjust_stocktake FOREIGN KEY (stocktake_order_id, warehouse_id)
        REFERENCES wms_stocktake_order (id, warehouse_id),
    CONSTRAINT ck_adjust_order_status CHECK (status IN (0, 1, 2, 3)),
    CONSTRAINT ck_adjust_order_no CHECK (REGEXP_LIKE(order_no, '^TZ[0-9]{12}$', 'c'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存调整单';

CREATE TABLE IF NOT EXISTS wms_adjust_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    batch_no VARCHAR(64) NOT NULL DEFAULT 'NO_BATCH',
    adjust_type VARCHAR(4) NOT NULL COMMENT 'GAIN 盘盈，LOSS 盘亏',
    adjust_qty DECIMAL(18, 3) NOT NULL COMMENT '调整绝对数量',
    reason VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    KEY idx_adjust_item_order (order_id, is_deleted),
    CONSTRAINT fk_adjust_item_order FOREIGN KEY (order_id, warehouse_id)
        REFERENCES wms_adjust_order (id, warehouse_id),
    CONSTRAINT fk_adjust_item_product FOREIGN KEY (product_id) REFERENCES wms_product (id),
    CONSTRAINT fk_adjust_item_location FOREIGN KEY (warehouse_id, location_id) REFERENCES wms_location (warehouse_id, id),
    CONSTRAINT ck_adjust_item_batch CHECK (CHAR_LENGTH(TRIM(batch_no)) > 0),
    CONSTRAINT ck_adjust_item_type CHECK (adjust_type IN ('GAIN', 'LOSS')),
    CONSTRAINT ck_adjust_item_qty CHECK (adjust_qty > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存调整单明细';

CREATE TABLE IF NOT EXISTS wms_freeze_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no CHAR(14) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    warehouse_id BIGINT NOT NULL,
    status TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 草稿，1 待审核，2 已生效，3 已驳回',
    remark VARCHAR(500) NULL,
    submitted_at DATETIME(3) NULL,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME(3) NULL,
    reject_reason VARCHAR(500) NULL,
    posted_at DATETIME(3) NULL COMMENT '审核生效或盘点完结时间',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    UNIQUE KEY uk_freeze_order_no (order_no),
    UNIQUE KEY uk_freeze_order_warehouse (id, warehouse_id),
    KEY idx_freeze_order_status (status, is_deleted, created_at),
    KEY idx_freeze_order_warehouse (warehouse_id, is_deleted, created_at),
    KEY idx_freeze_order_posted (posted_at),
    CONSTRAINT fk_freeze_warehouse FOREIGN KEY (warehouse_id) REFERENCES wms_warehouse (id),
    CONSTRAINT fk_freeze_reviewer FOREIGN KEY (reviewed_by) REFERENCES sys_user (id),
    CONSTRAINT ck_freeze_order_status CHECK (status IN (0, 1, 2, 3)),
    CONSTRAINT ck_freeze_order_no CHECK (REGEXP_LIKE(order_no, '^DJ[0-9]{12}$', 'c'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存冻结解冻单';

CREATE TABLE IF NOT EXISTS wms_freeze_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    batch_no VARCHAR(64) NOT NULL DEFAULT 'NO_BATCH',
    op_type VARCHAR(8) NOT NULL COMMENT 'FREEZE 冻结，UNFREEZE 解冻',
    quantity DECIMAL(18, 3) NOT NULL,
    reason VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    KEY idx_freeze_item_order (order_id, is_deleted),
    CONSTRAINT fk_freeze_item_order FOREIGN KEY (order_id, warehouse_id)
        REFERENCES wms_freeze_order (id, warehouse_id),
    CONSTRAINT fk_freeze_item_product FOREIGN KEY (product_id) REFERENCES wms_product (id),
    CONSTRAINT fk_freeze_item_location FOREIGN KEY (warehouse_id, location_id) REFERENCES wms_location (warehouse_id, id),
    CONSTRAINT ck_freeze_item_batch CHECK (CHAR_LENGTH(TRIM(batch_no)) > 0),
    CONSTRAINT ck_freeze_item_op CHECK (op_type IN ('FREEZE', 'UNFREEZE')),
    CONSTRAINT ck_freeze_item_qty CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存冻结解冻单明细';

-- 五、AI 会话、消息、运行记录与执行步骤

CREATE TABLE IF NOT EXISTS ai_session (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL DEFAULT '新对话',
    context_summary TEXT NULL,
    context_summary_through_message_id BIGINT NULL COMMENT '已压缩进摘要的最大消息 ID',
    context_state JSON NULL COMMENT '已确认的仓库、商品、批次与待补充字段',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    KEY idx_ai_session_user_updated (user_id, is_deleted, updated_at DESC),
    CONSTRAINT fk_ai_session_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 对话会话';

CREATE TABLE IF NOT EXISTS ai_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role VARCHAR(9) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content LONGTEXT NOT NULL,
    expert_count INT UNSIGNED NOT NULL DEFAULT 0,
    tool_call_count INT UNSIGNED NOT NULL DEFAULT 0,
    total_duration_ms INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_message_session_id (session_id, id),
    KEY idx_ai_message_history (session_id, is_deleted, created_at, id),
    CONSTRAINT fk_ai_message_session FOREIGN KEY (session_id) REFERENCES ai_session (id) ON DELETE CASCADE,
    CONSTRAINT ck_ai_message_role CHECK (role IN ('user', 'assistant'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 对话消息';

CREATE TABLE IF NOT EXISTS ai_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_message_id BIGINT NOT NULL,
    assistant_message_id BIGINT NOT NULL COMMENT '可先保存空消息以记录流式进度',
    client_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '客户端请求标识，用于请求级去重',
    operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '本轮业务的稳定操作标识',
    status VARCHAR(12) NOT NULL DEFAULT 'running',
    started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    heartbeat_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '执行者心跳，用于租约判定',
    finished_at DATETIME(3) NULL,
    error_message VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_run_assistant (assistant_message_id),
    UNIQUE KEY uk_ai_run_client_request (session_id, client_request_id),
    KEY idx_ai_run_session_status (session_id, status, started_at),
    CONSTRAINT fk_ai_run_user_message FOREIGN KEY (session_id, user_message_id)
        REFERENCES ai_message (session_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_run_assistant_message FOREIGN KEY (session_id, assistant_message_id)
        REFERENCES ai_message (session_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_ai_run_status CHECK (status IN ('running', 'success', 'error', 'interrupted')),
    CONSTRAINT ck_ai_run_dates CHECK (finished_at IS NULL OR finished_at >= started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单轮 AI 运行，步骤通过回复消息关联';

CREATE TABLE IF NOT EXISTS ai_execution_step (
    id BIGINT NOT NULL AUTO_INCREMENT,
    message_id BIGINT NOT NULL,
    step_order INT UNSIGNED NOT NULL COMMENT '同一工具调用的开始与结束共用序号',
    event_type VARCHAR(16) NOT NULL,
    agent_name VARCHAR(64) NULL,
    call_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '一次专家或工具调用的标识',
    parent_call_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '上级专家调用标识',
    tool_name VARCHAR(96) NULL,
    tool_args JSON NULL,
    result_summary TEXT NULL,
    duration_ms INT UNSIGNED NULL,
    status VARCHAR(8) NOT NULL DEFAULT 'success',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_step_event (message_id, step_order, event_type),
    KEY idx_ai_step_history (message_id, step_order, id),
    KEY idx_ai_step_call (message_id, call_id),
    CONSTRAINT fk_ai_step_message FOREIGN KEY (message_id) REFERENCES ai_message (id) ON DELETE CASCADE,
    CONSTRAINT ck_ai_step_order CHECK (step_order > 0),
    CONSTRAINT ck_ai_step_type CHECK (
        event_type IN ('dispatch', 'agent_start', 'tool_start', 'tool_end', 'agent_end', 'summarize')
    ),
    CONSTRAINT ck_ai_step_status CHECK (status IN ('running', 'success', 'error'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 执行步骤与回放事件';

CREATE TABLE IF NOT EXISTS ai_draft_operation (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '一轮运行的稳定操作标识',
    operation_key VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '草稿幂等键，与单据 request_key 一致',
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    order_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'inbound 或 outbound',
    order_id BIGINT NOT NULL,
    order_no CHAR(14) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_count INT UNSIGNED NOT NULL DEFAULT 0,
    status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'created',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_by BIGINT NULL COMMENT '创建人 ID，NULL 表示系统写入',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),

    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_draft_operation_key (operation_key),
    UNIQUE KEY uk_ai_draft_order (order_type, order_id),
    KEY idx_ai_draft_run (run_id),
    KEY idx_ai_draft_session (session_id, created_at),
    CONSTRAINT fk_ai_draft_run FOREIGN KEY (run_id) REFERENCES ai_run (id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_draft_session FOREIGN KEY (session_id) REFERENCES ai_session (id) ON DELETE CASCADE,
    CONSTRAINT ck_ai_draft_order_type CHECK (order_type IN ('inbound', 'outbound')),
    CONSTRAINT ck_ai_draft_status CHECK (status IN ('created', 'failed'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 草稿操作回执，断流后仍可定位单号';

-- 六、演示数据

-- 只有各独立数据域都为空时才导入固定 ID。命名锁串行化脚本的演示数据初始化。
SET @wms_seed_enabled = COALESCE(@wms_seed_demo, 1);
SET @wms_seed_lock = IF(@wms_seed_enabled <> 0, GET_LOCK('wms_db.seed_demo', 10), NULL);
SET @wms_previous_time_zone = @@session.time_zone;
SET time_zone = '+08:00';

START TRANSACTION;

SET @wms_load_demo = COALESCE(@wms_seed_lock = 1, 0)
    AND NOT EXISTS (SELECT 1 FROM sys_user)
    AND NOT EXISTS (SELECT 1 FROM sys_role)
    AND NOT EXISTS (SELECT 1 FROM sys_menu)
    AND NOT EXISTS (SELECT 1 FROM sys_token_blacklist)
    AND NOT EXISTS (SELECT 1 FROM wms_warehouse)
    AND NOT EXISTS (SELECT 1 FROM wms_category)
    AND NOT EXISTS (SELECT 1 FROM wms_supplier)
    AND NOT EXISTS (SELECT 1 FROM wms_customer)
    AND NOT EXISTS (SELECT 1 FROM wms_product)
    AND NOT EXISTS (SELECT 1 FROM wms_order_sequence);

SET @wms_demo_now = CURRENT_TIMESTAMP(3);
SET @wms_demo_day = DATE(@wms_demo_now);
SET @wms_ai_1_at = @wms_demo_now - INTERVAL 3 MINUTE;
SET @wms_ai_2_at = @wms_demo_now - INTERVAL 2 SECOND;
SET @wms_ai_3_at = @wms_demo_now - INTERVAL 1 MINUTE;
SET @wms_out_draft_at = @wms_ai_2_at + INTERVAL 275000 MICROSECOND;
SET @wms_master_at = TIMESTAMP(@wms_demo_day - INTERVAL 7 DAY, '08:00:00');
SET @wms_bulk_at = TIMESTAMP(@wms_demo_day - INTERVAL 6 DAY, '09:00:00');
SET @wms_wipes_at = TIMESTAMP(@wms_demo_day - INTERVAL 5 DAY, '09:00:00');
SET @wms_freeze_at = TIMESTAMP(@wms_demo_day - INTERVAL 5 DAY, '10:00:00');
SET @wms_transfer_at = TIMESTAMP(@wms_demo_day - INTERVAL 4 DAY, '10:00:00');
SET @wms_unfreeze_at = TIMESTAMP(@wms_demo_day - INTERVAL 4 DAY, '11:00:00');
SET @wms_out_at = TIMESTAMP(@wms_demo_day - INTERVAL 3 DAY, '10:00:00');
SET @wms_reject_at = TIMESTAMP(@wms_demo_day - INTERVAL 2 DAY, '11:00:00');
SET @wms_count_at = TIMESTAMP(@wms_demo_day - INTERVAL 2 DAY, '16:00:00');
SET @wms_adjust_at = TIMESTAMP(@wms_demo_day - INTERVAL 2 DAY, '17:00:00');
SET @wms_wipes_expire = @wms_demo_day + INTERVAL 12 DAY;
SET @wms_wipes_produced = @wms_wipes_expire - INTERVAL 180 DAY;
SET @wms_chg_produced = @wms_demo_day - INTERVAL 30 DAY;

SET @wms_bulk_no = CONCAT('RK', DATE_FORMAT(@wms_bulk_at, '%Y%m%d'), '0001');
SET @wms_wipes_no = CONCAT('RK', DATE_FORMAT(@wms_wipes_at, '%Y%m%d'), '0001');
SET @wms_in_draft_no = CONCAT('RK', DATE_FORMAT(@wms_demo_day, '%Y%m%d'), '0001');
SET @wms_out_no = CONCAT('CK', DATE_FORMAT(@wms_out_at, '%Y%m%d'), '0001');
SET @wms_out_draft_no = CONCAT('CK', DATE_FORMAT(@wms_out_draft_at, '%Y%m%d'), '0001');
SET @wms_out_pending_no = CONCAT('CK', DATE_FORMAT(@wms_demo_day, '%Y%m%d'), '0002');
SET @wms_out_reject_no = CONCAT('CK', DATE_FORMAT(@wms_reject_at, '%Y%m%d'), '0001');
SET @wms_transfer_no = CONCAT('YK', DATE_FORMAT(@wms_transfer_at, '%Y%m%d'), '0001');
SET @wms_transfer_draft_no = CONCAT('YK', DATE_FORMAT(@wms_demo_day, '%Y%m%d'), '0001');
SET @wms_count_no = CONCAT('PD', DATE_FORMAT(@wms_count_at, '%Y%m%d'), '0001');
SET @wms_count_pending_no = CONCAT('PD', DATE_FORMAT(@wms_demo_day, '%Y%m%d'), '0001');
SET @wms_adjust_no = CONCAT('TZ', DATE_FORMAT(@wms_adjust_at, '%Y%m%d'), '0001');
SET @wms_adjust_pending_no = CONCAT('TZ', DATE_FORMAT(@wms_demo_day, '%Y%m%d'), '0001');
SET @wms_freeze_no = CONCAT('DJ', DATE_FORMAT(@wms_freeze_at, '%Y%m%d'), '0001');
SET @wms_unfreeze_no = CONCAT('DJ', DATE_FORMAT(@wms_unfreeze_at, '%Y%m%d'), '0001');
SET @wms_freeze_draft_no = CONCAT('DJ', DATE_FORMAT(@wms_demo_day, '%Y%m%d'), '0001');

-- 6.1 演示账号、菜单和按钮权限

INSERT INTO sys_user (
    id, username, password, nickname, status, created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, 'admin', '$2y$12$/wESc4XrwkiSqDbk0JAtsuVMTs2Q9LLxSzSfNGqAkc0OLrtnodyau', '仓储管理员', 1, CAST(@wms_master_at AS DATETIME(3)),
        CAST(@wms_master_at AS DATETIME(3))),
    ROW(2, 'operator', '$2y$12$vIO1MFLfn5E8IjBZzHKnNOfcC0MLyYTdBpClCnN8KVN96CStq83Ay', '仓库作业员', 1, CAST(@wms_master_at AS DATETIME(3)),
        CAST(@wms_master_at AS DATETIME(3))),
    ROW(3, 'user', '$2y$12$01G4DDfqndqfWu79MWhbiub7COBUltktBqP0sh0QGg3llUPosztbe', '普通用户', 1, CAST(@wms_master_at AS DATETIME(3)),
        CAST(@wms_master_at AS DATETIME(3)))
) AS demo
WHERE @wms_load_demo;

INSERT INTO sys_role (
    id, role_code, role_name, created_by
)
SELECT demo.*
FROM (VALUES
    ROW(1, 'ADMIN', '管理员', 1),
    ROW(2, 'OPERATOR', '仓库作业员', 1),
    ROW(3, 'USER', '普通用户', 1)
) AS demo
WHERE @wms_load_demo;

INSERT INTO sys_user_role (
    user_id, role_id, created_by
)
SELECT demo.*
FROM (VALUES
    ROW(1, 1, 1),
    ROW(2, 2, 1),
    ROW(3, 3, 1)
) AS demo
WHERE @wms_load_demo;

INSERT INTO sys_menu (
    id, parent_id, menu_name, path, component, icon, menu_type, perms, sort_order, created_by
)
SELECT demo.*
FROM (VALUES
    ROW(1, NULL, '仓储看板', '/admin/dashboard', 'admin/dashboard/Index', 'DataAnalysis', 'C', 'wms:dashboard:view', 10, 1),
    ROW(10, NULL, '基础资料', '/admin/master', NULL, 'Box', 'M', NULL, 20, 1),
    ROW(20, NULL, '作业单据', '/admin/orders', NULL, 'Tickets', 'M', NULL, 30, 1),
    ROW(30, NULL, '实时库存', '/admin/inventory', 'admin/inventory/Index', 'Goods', 'C', 'wms:inventory:list', 40, 1),
    ROW(31, NULL, '库存预警', '/admin/alerts', 'admin/alert/Index', 'Bell', 'C', 'wms:alert:list', 50, 1),
    ROW(40, NULL, '系统管理', '/admin/system', NULL, 'Setting', 'M', NULL, 60, 1),
    ROW(50, NULL, 'AI 助手', '/admin/agent', 'agent/AgentChat', 'ChatDotRound', 'C', 'ai:chat:use', 70, 1),
    ROW(60, NULL, '前台首页', '/portal', 'portal/Home', 'House', 'C', 'portal:home:view', 10, 1),
    ROW(11, 10, '仓库', '/admin/master/warehouse', 'admin/master/Warehouse', NULL, 'C', 'wms:warehouse:list', 10, 1),
    ROW(12, 10, '库区', '/admin/master/area', 'admin/master/Area', NULL, 'C', 'wms:area:list', 20, 1),
    ROW(13, 10, '库位', '/admin/master/location', 'admin/master/Location', NULL, 'C', 'wms:location:list', 30, 1),
    ROW(14, 10, '商品分类', '/admin/master/category', 'admin/master/Category', NULL, 'C', 'wms:category:list', 40, 1),
    ROW(15, 10, '商品档案', '/admin/master/product', 'admin/master/Product', NULL, 'C', 'wms:product:list', 50, 1),
    ROW(16, 10, '供应商', '/admin/master/supplier', 'admin/master/Supplier', NULL, 'C', 'wms:supplier:list', 60, 1),
    ROW(17, 10, '客户', '/admin/master/customer', 'admin/master/Customer', NULL, 'C', 'wms:customer:list', 70, 1),
    ROW(21, 20, '入库单', '/admin/orders/inbound', 'admin/order/Inbound', NULL, 'C', 'wms:inbound:list', 10, 1),
    ROW(22, 20, '出库单', '/admin/orders/outbound', 'admin/order/Outbound', NULL, 'C', 'wms:outbound:list', 20, 1),
    ROW(23, 20, '移库单', '/admin/orders/transfer', 'admin/order/Transfer', NULL, 'C', 'wms:transfer:list', 30, 1),
    ROW(24, 20, '库存调整单', '/admin/orders/adjust', 'admin/order/Adjust', NULL, 'C', 'wms:adjust:list', 40, 1),
    ROW(25, 20, '库存盘点单', '/admin/orders/stocktake', 'admin/order/Stocktake', NULL, 'C', 'wms:stocktake:list', 50, 1),
    ROW(26, 20, '冻结与解冻单', '/admin/orders/freeze', 'admin/order/Freeze', NULL, 'C', 'wms:freeze:list', 60, 1),
    ROW(41, 40, '用户管理', '/admin/system/users', 'admin/system/User', NULL, 'C', 'sys:user:list', 10, 1),
    ROW(42, 40, '角色管理', '/admin/system/roles', 'admin/system/Role', NULL, 'C', 'sys:role:list', 20, 1),
    ROW(43, 40, '菜单管理', '/admin/system/menus', 'admin/system/Menu', NULL, 'C', 'sys:menu:list', 30, 1),
    ROW(61, 60, '库存查询', '/portal/inventory', 'portal/Inventory', NULL, 'C', 'wms:inventory:list', 10, 1),
    ROW(62, 60, 'AI 工作台', '/portal/agent', 'agent/AgentChat', NULL, 'C', 'ai:chat:use', 20, 1)
) AS demo
WHERE @wms_load_demo
ORDER BY demo.column_0;

-- 基础资料和系统管理的新增、修改、删除按钮。
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort_order, created_by)
SELECT
    1000 + menu.id * 10 + action.action_id,
    menu.id,
    action.label,
    'F',
    CONCAT(SUBSTRING_INDEX(menu.perms, ':', 2), ':', action.perm_suffix),
    action.action_id,
    1
FROM sys_menu AS menu
CROSS JOIN (
    VALUES ROW(1, '新增', 'add'), ROW(2, '修改', 'edit'), ROW(3, '删除', 'remove')
) AS action (action_id, label, perm_suffix)
WHERE @wms_load_demo AND menu.id IN (11, 12, 13, 14, 15, 16, 17, 41, 42, 43);

-- 六类单据的动作权限与具体接口对应。
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, perms, sort_order, created_by)
SELECT
    1000 + menu.id * 10 + action.action_id,
    menu.id,
    action.label,
    'F',
    CONCAT(SUBSTRING_INDEX(menu.perms, ':', 2), ':', action.perm_suffix),
    action.action_id,
    1
FROM sys_menu AS menu
CROSS JOIN (
    VALUES
        ROW(1, '新建单据', 'add'),
        ROW(2, '修改草稿', 'edit'),
        ROW(3, '删除草稿', 'remove'),
        ROW(4, '提交审核', 'submit'),
        ROW(5, '审核生效', 'approve'),
        ROW(6, '驳回单据', 'reject'),
        ROW(7, '录入盘点', 'count'),
        ROW(8, '完结盘点', 'complete')
) AS action (action_id, label, perm_suffix)
WHERE @wms_load_demo
    AND (
        (menu.id IN (21, 22) AND action.action_id BETWEEN 1 AND 6)
        OR (menu.id IN (23, 24, 26) AND action.action_id IN (1, 5))
        OR (menu.id = 25 AND action.action_id IN (1, 7, 8))
    );

INSERT INTO sys_menu (
    id, parent_id, menu_name, menu_type, perms, sort_order, created_by
)
SELECT demo.*
FROM (VALUES
    ROW(1301, 30, '库存流水', 'F', 'wms:inventory:logs', 10, 1),
    ROW(1302, 30, '导出库存', 'F', 'wms:inventory:export', 20, 1),
    ROW(1414, 41, '分配角色', 'F', 'sys:user:roles', 10, 1),
    ROW(1415, 41, '重置密码', 'F', 'sys:user:password', 20, 1),
    ROW(1424, 42, '分配菜单', 'F', 'sys:role:menus', 10, 1)
) AS demo
WHERE @wms_load_demo;

INSERT INTO sys_role_menu (role_id, menu_id, created_by)
SELECT 1, id, 1 FROM sys_menu WHERE @wms_load_demo AND id NOT IN (60, 61, 62)
UNION ALL
SELECT 2, id, 1 FROM sys_menu
WHERE @wms_load_demo AND (
    id IN (1, 10, 11, 12, 13, 14, 15, 16, 17, 20, 21, 22, 23, 24, 25, 26, 30, 31, 50)
    OR id IN (1211, 1212, 1213, 1214, 1221, 1222, 1223, 1224, 1231, 1241, 1251, 1257, 1261, 1301, 1302)
)
UNION ALL
SELECT 3, id, 1 FROM sys_menu WHERE @wms_load_demo AND id IN (60, 61, 62);

INSERT INTO sys_token_blacklist (
    jti, expire_at, created_at
)
SELECT demo.*
FROM (VALUES
    ROW('29887c19-251a-42dc-be4e-fa20a3a4c4b1', CAST(@wms_demo_now AS DATETIME(3)) + INTERVAL 1 DAY, CAST(@wms_demo_now AS DATETIME(3))),
    ROW('29887c19-251a-42dc-be4e-fa20a3a4c4b2', CAST(@wms_demo_now AS DATETIME(3)) - INTERVAL 1 DAY, CAST(@wms_demo_now AS DATETIME(3)) - INTERVAL 2 DAY)
) AS demo
WHERE @wms_load_demo;

-- 6.2 两个仓库和预警演示所需的基础资料

INSERT INTO wms_warehouse (
    id, code, name, address, manager, created_by, created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, 'WH-CENTRAL', '中央仓', '演示园区 A 栋', '中央仓库管', 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3))),
    ROW(2, 'WH-EAST', '华东仓', '演示园区 B 栋', '华东仓库管', 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3)))
) AS demo
WHERE @wms_load_demo;

INSERT INTO wms_area (
    id, warehouse_id, code, name, area_type, created_by, created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(11, 1, 'REC', '收货区', 'RECEIVING', 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3))),
    ROW(12, 1, 'STO', '存储区', 'STORAGE', 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3))),
    ROW(13, 1, 'PIC', '拣货区', 'PICKING', 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3))),
    ROW(14, 1, 'NG', '不良品区', 'DEFECTIVE', 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3))),
    ROW(21, 2, 'STO', '存储区', 'STORAGE', 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3))),
    ROW(22, 2, 'PIC', '拣货区', 'PICKING', 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3)))
) AS demo
WHERE @wms_load_demo;

INSERT INTO wms_location (
    id, warehouse_id, area_id, code, max_capacity, created_by, created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(101, 1, 11, 'REC-A01', 500, 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3))),
    ROW(102, 1, 12, 'STO-A01', 1000, 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3))),
    ROW(103, 1, 13, 'PIC-A01', 200, 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3))),
    ROW(104, 1, 14, 'NG-A01', 100, 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3))),
    ROW(105, 1, 12, 'STO-A02', 1000, 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3))),
    ROW(201, 2, 21, 'STO-B01', 800, 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3))),
    ROW(202, 2, 22, 'PIC-B01', 200, 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3)))
) AS demo
WHERE @wms_load_demo;

INSERT INTO wms_category (
    id, parent_id, name, sort_order, created_by, created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, NULL, '电子配件', 10, 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3))),
    ROW(2, NULL, '仓储耗材', 20, 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3))),
    ROW(11, 1, '充电器', 10, 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3))),
    ROW(12, 1, '线材', 20, 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3))),
    ROW(21, 2, '清洁耗材', 10, 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3)))
) AS demo
WHERE @wms_load_demo
ORDER BY demo.column_0;

INSERT INTO wms_supplier (
    id, code, name, contact, phone, address, created_by, created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, 'SUP-ELEC', '华源电子', '电子品类联系人', NULL, '演示供应园 A 区', 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3))),
    ROW(2, 'SUP-SUPPLY', '安洁用品', '耗材品类联系人', NULL, '演示供应园 B 区', 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3)))
) AS demo
WHERE @wms_load_demo;

INSERT INTO wms_customer (
    id, code, name, contact, phone, address, created_by, created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, 'CUS-RETAIL', '远航零售', '零售收货联系人', NULL, '演示配送点一', 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3))),
    ROW(2, 'CUS-SERVICE', '同城服务站', '服务站收货联系人', NULL, '演示配送点二', 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3)))
) AS demo
WHERE @wms_load_demo;

INSERT INTO wms_product (
    id, sku_code, name, category_id, spec, unit, batch_managed, min_stock, max_stock, shelf_life_days,
    default_supplier_id, reference_price, created_by, created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, 'CHG-20W', '20W 充电器', 11, 'USB-C PD 20W', '件', 1, 30, 120, 0, 1, 29.9, 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3))),
    ROW(2, 'CHG-65W', '65W 充电器', 11, 'USB-C GaN 65W', '件', 1, 20, 100, 0, 1, 79, 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3))),
    ROW(3, 'CBL-C100', 'Type-C 数据线', 12, '1 米，60W', '条', 0, 20, 150, 0, 1, 8.8, 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3))),
    ROW(4, 'WIP-80', '设备清洁湿巾', 21, '80 片装', '包', 1, 10, 80, 180, 2, 12.5, 1, CAST(@wms_master_at AS DATETIME(3)), CAST(@wms_master_at AS DATETIME(3)))
) AS demo
WHERE @wms_load_demo;

-- 6.3 六类单据及编号序列

INSERT INTO wms_inbound_order (
    id, order_no, warehouse_id, supplier_id, source, request_key, status, remark, submitted_at, reviewed_by,
    reviewed_at, reject_reason, posted_at, created_by, created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, @wms_bulk_no, 1, 1, 'MANUAL', NULL, 2, '中央仓电子配件到货', CAST(@wms_bulk_at AS DATETIME(3)) - INTERVAL 20 MINUTE, 1, CAST(@wms_bulk_at AS DATETIME(3)),
        NULL, CAST(@wms_bulk_at AS DATETIME(3)), 2, CAST(@wms_bulk_at AS DATETIME(3)) - INTERVAL 30 MINUTE, CAST(@wms_bulk_at AS DATETIME(3))),
    ROW(2, @wms_wipes_no, 1, 2, 'MANUAL', NULL, 2, '湿巾到货，已记录效期', CAST(@wms_wipes_at AS DATETIME(3)) - INTERVAL 20 MINUTE, 1, CAST(@wms_wipes_at AS DATETIME(3)),
        NULL, CAST(@wms_wipes_at AS DATETIME(3)), 2, CAST(@wms_wipes_at AS DATETIME(3)) - INTERVAL 30 MINUTE, CAST(@wms_wipes_at AS DATETIME(3))),
    ROW(3, @wms_in_draft_no, 1, 1, 'AI_AGENT', 'demo-inbound-20w-b003', 0, 'AI 代建充电器补货草稿', NULL, NULL, NULL, NULL,
        NULL, 2, CAST(@wms_demo_now AS DATETIME(3)), CAST(@wms_demo_now AS DATETIME(3)))
) AS demo
WHERE @wms_load_demo;

INSERT INTO wms_inbound_item (
    id, order_id, warehouse_id, product_id, location_id, batch_no, production_date, expire_date, quantity, unit_price,
    created_by, created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, 1, 1, 1, 102, 'B001', CAST(@wms_chg_produced AS DATE), NULL, 100, 29.9, 2, CAST(@wms_bulk_at AS DATETIME(3)) - INTERVAL 30 MINUTE,
        CAST(@wms_bulk_at AS DATETIME(3)) - INTERVAL 30 MINUTE),
    ROW(2, 1, 1, 2, 102, 'B002', CAST(@wms_chg_produced AS DATE), NULL, 70, 79, 2, CAST(@wms_bulk_at AS DATETIME(3)) - INTERVAL 30 MINUTE,
        CAST(@wms_bulk_at AS DATETIME(3)) - INTERVAL 30 MINUTE),
    ROW(3, 1, 1, 3, 105, 'NO_BATCH', NULL, NULL, 200, 8.8, 2, CAST(@wms_bulk_at AS DATETIME(3)) - INTERVAL 30 MINUTE,
        CAST(@wms_bulk_at AS DATETIME(3)) - INTERVAL 30 MINUTE),
    ROW(4, 2, 1, 4, 103, 'W001', CAST(@wms_wipes_produced AS DATE), CAST(@wms_wipes_expire AS DATE), 24, 12.5, 2,
        CAST(@wms_wipes_at AS DATETIME(3)) - INTERVAL 30 MINUTE, CAST(@wms_wipes_at AS DATETIME(3)) - INTERVAL 30 MINUTE),
    ROW(5, 3, 1, 1, 102, 'B003', CAST(@wms_demo_day AS DATE), NULL, 30, 29.9, 2, CAST(@wms_demo_now AS DATETIME(3)), CAST(@wms_demo_now AS DATETIME(3)))
) AS demo
WHERE @wms_load_demo;

INSERT INTO wms_outbound_order (
    id, order_no, warehouse_id, customer_id, source, request_key, status, remark, submitted_at, reviewed_by,
    reviewed_at, reject_reason, posted_at, created_by, created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, @wms_out_no, 1, 1, 'AI_AGENT', 'demo-outbound-20w-approved', 2, 'AI 代建并经管理员审核的出库单',
        CAST(@wms_out_at AS DATETIME(3)) - INTERVAL 20 MINUTE, 1, CAST(@wms_out_at AS DATETIME(3)), NULL, CAST(@wms_out_at AS DATETIME(3)), 2, CAST(@wms_out_at AS DATETIME(3)) - INTERVAL 30 MINUTE,
        CAST(@wms_out_at AS DATETIME(3))),
    ROW(2, @wms_out_draft_no, 1, 1, 'AI_AGENT', 'demo-outbound-20w-b001', 0, 'AI 代建待核验出库草稿', NULL, NULL, NULL, NULL,
        NULL, 2, CAST(@wms_out_draft_at AS DATETIME(3)), CAST(@wms_out_draft_at AS DATETIME(3))),
    ROW(3, @wms_out_pending_no, 1, 2, 'MANUAL', NULL, 1, '待审 65W 充电器出库', CAST(@wms_demo_now AS DATETIME(3)), NULL, NULL, NULL, NULL, 2,
        CAST(@wms_demo_now AS DATETIME(3)), CAST(@wms_demo_now AS DATETIME(3))),
    ROW(4, @wms_out_reject_no, 1, 1, 'MANUAL', NULL, 3, '20W 充电器出库申请', CAST(@wms_reject_at AS DATETIME(3)) - INTERVAL 20 MINUTE, 1,
        CAST(@wms_reject_at AS DATETIME(3)), 'B001 可用量只有 20 件，申请出库 90 件', NULL, 2, CAST(@wms_reject_at AS DATETIME(3)) - INTERVAL 30 MINUTE, CAST(@wms_reject_at AS DATETIME(3)))
) AS demo
WHERE @wms_load_demo;

INSERT INTO wms_outbound_item (
    id, order_id, warehouse_id, product_id, location_id, batch_no, quantity, unit_price, created_by, created_at,
    updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, 1, 1, 1, 102, 'B001', 60, 29.9, 2, CAST(@wms_out_at AS DATETIME(3)) - INTERVAL 30 MINUTE, CAST(@wms_out_at AS DATETIME(3)) - INTERVAL 30 MINUTE),
    ROW(2, 2, 1, 1, 102, 'B001', 10, 29.9, 2, CAST(@wms_out_draft_at AS DATETIME(3)), CAST(@wms_out_draft_at AS DATETIME(3))),
    ROW(3, 3, 1, 2, 102, 'B002', 5, 79, 2, CAST(@wms_demo_now AS DATETIME(3)), CAST(@wms_demo_now AS DATETIME(3))),
    ROW(4, 4, 1, 1, 102, 'B001', 90, 29.9, 2, CAST(@wms_reject_at AS DATETIME(3)) - INTERVAL 30 MINUTE, CAST(@wms_reject_at AS DATETIME(3)) - INTERVAL 30 MINUTE)
) AS demo
WHERE @wms_load_demo;

INSERT INTO wms_transfer_order (
    id, order_no, from_warehouse_id, to_warehouse_id, status, remark, submitted_at, reviewed_by, reviewed_at,
    reject_reason, posted_at, created_by, created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, @wms_transfer_no, 1, 2, 2, '中央仓调拨 65W 充电器至华东仓', CAST(@wms_transfer_at AS DATETIME(3)) - INTERVAL 20 MINUTE, 1, CAST(@wms_transfer_at AS DATETIME(3)),
        NULL, CAST(@wms_transfer_at AS DATETIME(3)), 2, CAST(@wms_transfer_at AS DATETIME(3)) - INTERVAL 30 MINUTE, CAST(@wms_transfer_at AS DATETIME(3))),
    ROW(2, @wms_transfer_draft_no, 1, 1, 0, '同仓移库草稿', NULL, NULL, NULL, NULL, NULL, 2, CAST(@wms_demo_now AS DATETIME(3)), CAST(@wms_demo_now AS DATETIME(3)))
) AS demo
WHERE @wms_load_demo;

INSERT INTO wms_transfer_item (
    id, order_id, from_warehouse_id, to_warehouse_id, product_id, from_location_id, to_location_id, batch_no, quantity,
    created_by, created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, 1, 1, 2, 2, 102, 201, 'B002', 15, 2, CAST(@wms_transfer_at AS DATETIME(3)) - INTERVAL 30 MINUTE,
        CAST(@wms_transfer_at AS DATETIME(3)) - INTERVAL 30 MINUTE),
    ROW(2, 2, 1, 1, 2, 102, 103, 'B002', 5, 2, CAST(@wms_demo_now AS DATETIME(3)), CAST(@wms_demo_now AS DATETIME(3)))
) AS demo
WHERE @wms_load_demo;

INSERT INTO wms_stocktake_order (
    id, order_no, warehouse_id, status, remark, submitted_at, reviewed_by, reviewed_at, reject_reason, posted_at,
    created_by, created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, @wms_count_no, 1, 2, '中央仓数据线盘点完成，账实差异 -10 条', CAST(@wms_count_at AS DATETIME(3)) - INTERVAL 20 MINUTE, 1, CAST(@wms_count_at AS DATETIME(3)), NULL,
        CAST(@wms_count_at AS DATETIME(3)), 2, CAST(@wms_count_at AS DATETIME(3)) - INTERVAL 30 MINUTE, CAST(@wms_count_at AS DATETIME(3))),
    ROW(2, @wms_count_pending_no, 1, 1, '数据线复盘，等待录入实际库存', CAST(@wms_demo_now AS DATETIME(3)), NULL, NULL, NULL, NULL, 2, CAST(@wms_demo_now AS DATETIME(3)),
        CAST(@wms_demo_now AS DATETIME(3)))
) AS demo
WHERE @wms_load_demo;

INSERT INTO wms_stocktake_item (
    id, order_id, warehouse_id, product_id, location_id, batch_no, book_qty, actual_qty, inventory_version, created_by,
    created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, 1, 1, 3, 105, 'NO_BATCH', 200, 190, 1, 2, CAST(@wms_count_at AS DATETIME(3)) - INTERVAL 30 MINUTE,
        CAST(@wms_count_at AS DATETIME(3)) - INTERVAL 30 MINUTE),
    ROW(2, 2, 1, 3, 105, 'NO_BATCH', 190, NULL, 2, 2, CAST(@wms_demo_now AS DATETIME(3)), CAST(@wms_demo_now AS DATETIME(3)))
) AS demo
WHERE @wms_load_demo;

INSERT INTO wms_adjust_order (
    id, order_no, warehouse_id, stocktake_order_id, status, remark, submitted_at, reviewed_by, reviewed_at,
    reject_reason, posted_at, created_by, created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, @wms_adjust_no, 1, 1, 2, '由盘点差异生成，盘亏 10 条', CAST(@wms_adjust_at AS DATETIME(3)) - INTERVAL 20 MINUTE, 1, CAST(@wms_adjust_at AS DATETIME(3)), NULL,
        CAST(@wms_adjust_at AS DATETIME(3)), 2, CAST(@wms_adjust_at AS DATETIME(3)) - INTERVAL 30 MINUTE, CAST(@wms_adjust_at AS DATETIME(3))),
    ROW(2, @wms_adjust_pending_no, 1, NULL, 1, '待复核湿巾盘盈', CAST(@wms_demo_now AS DATETIME(3)), NULL, NULL, NULL, NULL, 2, CAST(@wms_demo_now AS DATETIME(3)),
        CAST(@wms_demo_now AS DATETIME(3)))
) AS demo
WHERE @wms_load_demo;

INSERT INTO wms_adjust_item (
    id, order_id, warehouse_id, product_id, location_id, batch_no, adjust_type, adjust_qty, reason, created_by,
    created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, 1, 1, 3, 105, 'NO_BATCH', 'LOSS', 10, '盘点实盘 190 条，账面 200 条', 2, CAST(@wms_adjust_at AS DATETIME(3)) - INTERVAL 30 MINUTE,
        CAST(@wms_adjust_at AS DATETIME(3)) - INTERVAL 30 MINUTE),
    ROW(2, 2, 1, 4, 103, 'W001', 'GAIN', 3, '复核现场账实差异', 2, CAST(@wms_demo_now AS DATETIME(3)), CAST(@wms_demo_now AS DATETIME(3)))
) AS demo
WHERE @wms_load_demo;

INSERT INTO wms_freeze_order (
    id, order_no, warehouse_id, status, remark, submitted_at, reviewed_by, reviewed_at, reject_reason, posted_at,
    created_by, created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, @wms_freeze_no, 1, 2, '质检冻结 B001 批次', CAST(@wms_freeze_at AS DATETIME(3)) - INTERVAL 20 MINUTE, 1, CAST(@wms_freeze_at AS DATETIME(3)), NULL,
        CAST(@wms_freeze_at AS DATETIME(3)), 2, CAST(@wms_freeze_at AS DATETIME(3)) - INTERVAL 30 MINUTE, CAST(@wms_freeze_at AS DATETIME(3))),
    ROW(2, @wms_unfreeze_no, 1, 2, 'B001 批次部分解冻', CAST(@wms_unfreeze_at AS DATETIME(3)) - INTERVAL 20 MINUTE, 1, CAST(@wms_unfreeze_at AS DATETIME(3)), NULL,
        CAST(@wms_unfreeze_at AS DATETIME(3)), 2, CAST(@wms_unfreeze_at AS DATETIME(3)) - INTERVAL 30 MINUTE, CAST(@wms_unfreeze_at AS DATETIME(3))),
    ROW(3, @wms_freeze_draft_no, 1, 0, 'B002 批次待复核冻结草稿', NULL, NULL, NULL, NULL, NULL, 2, CAST(@wms_demo_now AS DATETIME(3)), CAST(@wms_demo_now AS DATETIME(3)))
) AS demo
WHERE @wms_load_demo;

INSERT INTO wms_freeze_item (
    id, order_id, warehouse_id, product_id, location_id, batch_no, op_type, quantity, reason, created_by, created_at,
    updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, 1, 1, 1, 102, 'B001', 'FREEZE', 25, '质检抽样保留', 2, CAST(@wms_freeze_at AS DATETIME(3)) - INTERVAL 30 MINUTE,
        CAST(@wms_freeze_at AS DATETIME(3)) - INTERVAL 30 MINUTE),
    ROW(2, 2, 1, 1, 102, 'B001', 'UNFREEZE', 5, '抽检通过后部分释放', 2, CAST(@wms_unfreeze_at AS DATETIME(3)) - INTERVAL 30 MINUTE,
        CAST(@wms_unfreeze_at AS DATETIME(3)) - INTERVAL 30 MINUTE),
    ROW(3, 3, 1, 2, 102, 'B002', 'FREEZE', 5, '待处理质检申请', 2, CAST(@wms_demo_now AS DATETIME(3)), CAST(@wms_demo_now AS DATETIME(3)))
) AS demo
WHERE @wms_load_demo;

-- 从演示单据反推各日期的最大流水，确保后续新单从下一号开始。
INSERT INTO wms_order_sequence (order_prefix, biz_date, last_no)
SELECT
    LEFT(orders.order_no, 2),
    STR_TO_DATE(SUBSTRING(orders.order_no, 3, 8), '%Y%m%d'),
    MAX(CAST(RIGHT(orders.order_no, 4) AS UNSIGNED))
FROM (
    SELECT order_no FROM wms_inbound_order
    UNION ALL SELECT order_no FROM wms_outbound_order
    UNION ALL SELECT order_no FROM wms_transfer_order
    UNION ALL SELECT order_no FROM wms_adjust_order
    UNION ALL SELECT order_no FROM wms_stocktake_order
    UNION ALL SELECT order_no FROM wms_freeze_order
) AS orders
WHERE @wms_load_demo
GROUP BY LEFT(orders.order_no, 2), STR_TO_DATE(SUBSTRING(orders.order_no, 3, 8), '%Y%m%d');

-- 6.4 库存余额与对应流水
-- 中央仓 B001 先入库 100 件、冻结 25 件、解冻 5 件、出库 60 件，现可用 20 件。
-- 数据线在库 190 条，超出 150 条上限。湿巾 W001 在导入当日距到期 12 天。

INSERT INTO wms_inventory (
    id, warehouse_id, location_id, product_id, batch_no, production_date, expire_date, quantity, frozen_quantity,
    version, created_by, created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, 1, 102, 1, 'B001', CAST(@wms_chg_produced AS DATE), NULL, 40, 20, 4, 1, CAST(@wms_bulk_at AS DATETIME(3)), CAST(@wms_out_at AS DATETIME(3))),
    ROW(2, 1, 102, 2, 'B002', CAST(@wms_chg_produced AS DATE), NULL, 55, 0, 2, 1, CAST(@wms_bulk_at AS DATETIME(3)), CAST(@wms_transfer_at AS DATETIME(3))),
    ROW(3, 1, 105, 3, 'NO_BATCH', NULL, NULL, 190, 0, 2, 1, CAST(@wms_bulk_at AS DATETIME(3)), CAST(@wms_adjust_at AS DATETIME(3))),
    ROW(4, 1, 103, 4, 'W001', CAST(@wms_wipes_produced AS DATE), CAST(@wms_wipes_expire AS DATE), 24, 0, 1, 1, CAST(@wms_wipes_at AS DATETIME(3)), CAST(@wms_wipes_at AS DATETIME(3))),
    ROW(5, 2, 201, 2, 'B002', CAST(@wms_chg_produced AS DATE), NULL, 15, 0, 1, 1, CAST(@wms_transfer_at AS DATETIME(3)), CAST(@wms_transfer_at AS DATETIME(3)))
) AS demo
WHERE @wms_load_demo;

INSERT INTO wms_inventory_log (
    id, inventory_id, warehouse_id, location_id, product_id, batch_no, biz_type, order_no, before_qty, change_qty,
    after_qty, before_frozen_qty, change_frozen_qty, after_frozen_qty, operator_id, created_by, created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, 1, 1, 102, 1, 'B001', 'INBOUND', @wms_bulk_no, 0, 100, 100, 0, 0, 0, 1, 1, CAST(@wms_bulk_at AS DATETIME(3)), CAST(@wms_bulk_at AS DATETIME(3))),
    ROW(2, 2, 1, 102, 2, 'B002', 'INBOUND', @wms_bulk_no, 0, 70, 70, 0, 0, 0, 1, 1, CAST(@wms_bulk_at AS DATETIME(3)), CAST(@wms_bulk_at AS DATETIME(3))),
    ROW(3, 3, 1, 105, 3, 'NO_BATCH', 'INBOUND', @wms_bulk_no, 0, 200, 200, 0, 0, 0, 1, 1, CAST(@wms_bulk_at AS DATETIME(3)), CAST(@wms_bulk_at AS DATETIME(3))),
    ROW(4, 4, 1, 103, 4, 'W001', 'INBOUND', @wms_wipes_no, 0, 24, 24, 0, 0, 0, 1, 1, CAST(@wms_wipes_at AS DATETIME(3)), CAST(@wms_wipes_at AS DATETIME(3))),
    ROW(5, 1, 1, 102, 1, 'B001', 'FREEZE', @wms_freeze_no, 100, 0, 100, 0, 25, 25, 1, 1, CAST(@wms_freeze_at AS DATETIME(3)), CAST(@wms_freeze_at AS DATETIME(3))),
    ROW(6, 2, 1, 102, 2, 'B002', 'TRANSFER_OUT', @wms_transfer_no, 70, -15, 55, 0, 0, 0, 1, 1, CAST(@wms_transfer_at AS DATETIME(3)),
        CAST(@wms_transfer_at AS DATETIME(3))),
    ROW(7, 5, 2, 201, 2, 'B002', 'TRANSFER_IN', @wms_transfer_no, 0, 15, 15, 0, 0, 0, 1, 1, CAST(@wms_transfer_at AS DATETIME(3)),
        CAST(@wms_transfer_at AS DATETIME(3))),
    ROW(8, 1, 1, 102, 1, 'B001', 'UNFREEZE', @wms_unfreeze_no, 100, 0, 100, 25, -5, 20, 1, 1, CAST(@wms_unfreeze_at AS DATETIME(3)),
        CAST(@wms_unfreeze_at AS DATETIME(3))),
    ROW(9, 1, 1, 102, 1, 'B001', 'OUTBOUND', @wms_out_no, 100, -60, 40, 20, 0, 20, 1, 1, CAST(@wms_out_at AS DATETIME(3)), CAST(@wms_out_at AS DATETIME(3))),
    ROW(10, 3, 1, 105, 3, 'NO_BATCH', 'ADJUST', @wms_adjust_no, 200, -10, 190, 0, 0, 0, 1, 1, CAST(@wms_adjust_at AS DATETIME(3)),
        CAST(@wms_adjust_at AS DATETIME(3)))
) AS demo
WHERE @wms_load_demo;

-- 6.5 AI 对话、已成功的草稿代建与中断回放

INSERT INTO ai_session (
    id, user_id, title, created_by, created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW('ca25b87b-bbd7-4efb-a747-2581c008aa01', 1, '中央仓充电器与补货建议', 1, CAST(@wms_ai_1_at AS DATETIME(3)),
        CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 950000 MICROSECOND),
    ROW('ca25b87b-bbd7-4efb-a747-2581c008aa02', 2, '代建充电器出库草稿', 2, CAST(@wms_ai_2_at AS DATETIME(3)),
        CAST(@wms_ai_2_at AS DATETIME(3)) + INTERVAL 600000 MICROSECOND),
    ROW('ca25b87b-bbd7-4efb-a747-2581c008aa03', 3, '临期批次查询', 3, CAST(@wms_ai_3_at AS DATETIME(3)),
        CAST(@wms_ai_3_at AS DATETIME(3)) + INTERVAL 430000 MICROSECOND)
) AS demo
WHERE @wms_load_demo;

INSERT INTO ai_message (
    id, session_id, role, content, expert_count, tool_call_count, total_duration_ms, created_by, created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, 'ca25b87b-bbd7-4efb-a747-2581c008aa01', 'user', '中央仓的充电器还够不够？不够的给个补货建议。', 0, 0, 0, 1, CAST(@wms_ai_1_at AS DATETIME(3)),
        CAST(@wms_ai_1_at AS DATETIME(3))),
    ROW(2, 'ca25b87b-bbd7-4efb-a747-2581c008aa01', 'assistant',
        CONCAT(
            '中央仓有 1 种充电器低于安全库存。', CHAR(10), CHAR(10),
            '| 商品 | 在库 | 冻结 | 可用 | 安全下限 | 补货缺口 |', CHAR(10),
            '| --- | ---: | ---: | ---: | ---: | ---: |', CHAR(10),
            '| 20W 充电器 | 40 | 20 | 20 | 30 | 10 |', CHAR(10),
            '| 65W 充电器 | 55 | 0 | 55 | 20 | 0 |', CHAR(10), CHAR(10),
            '20W 充电器的供应商是华源电子，补到安全下限需 10 件。'
        ),
        2, 2, 950, 1, CAST(@wms_ai_1_at AS DATETIME(3)), CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 950000 MICROSECOND),
    ROW(3, 'ca25b87b-bbd7-4efb-a747-2581c008aa02', 'user', '为远航零售代建中央仓 STO-A01 库位 B001 批次的 20W 充电器出库草稿，数量 10 件。', 0, 0,
        0, 2, CAST(@wms_ai_2_at AS DATETIME(3)), CAST(@wms_ai_2_at AS DATETIME(3))),
    ROW(4, 'ca25b87b-bbd7-4efb-a747-2581c008aa02', 'assistant',
        CONCAT('已为远航零售代建出库草稿 ', @wms_out_draft_no, '，数量 10 件。当前可用量仍为 20 件，草稿等待提交与审核。'), 1, 1, 600, 2, CAST(@wms_ai_2_at AS DATETIME(3)),
        CAST(@wms_ai_2_at AS DATETIME(3)) + INTERVAL 600000 MICROSECOND),
    ROW(5, 'ca25b87b-bbd7-4efb-a747-2581c008aa03', 'user', '中央仓有哪些批次在 30 天内到期？', 0, 0, 0, 3, CAST(@wms_ai_3_at AS DATETIME(3)), CAST(@wms_ai_3_at AS DATETIME(3))),
    ROW(6, 'ca25b87b-bbd7-4efb-a747-2581c008aa03', 'assistant', '查到中央仓有一批设备清洁湿巾临期，批次 W001。', 1, 1, 430, 3,
        CAST(@wms_ai_3_at AS DATETIME(3)), CAST(@wms_ai_3_at AS DATETIME(3)) + INTERVAL 430000 MICROSECOND)
) AS demo
WHERE @wms_load_demo;

INSERT INTO ai_run (
    id, session_id, user_message_id, assistant_message_id, status, started_at, finished_at, created_by, created_at,
    updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, 'ca25b87b-bbd7-4efb-a747-2581c008aa01', 1, 2, 'success', CAST(@wms_ai_1_at AS DATETIME(3)),
        CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 950000 MICROSECOND, 1, CAST(@wms_ai_1_at AS DATETIME(3)), CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 950000 MICROSECOND),
    ROW(2, 'ca25b87b-bbd7-4efb-a747-2581c008aa02', 3, 4, 'success', CAST(@wms_ai_2_at AS DATETIME(3)),
        CAST(@wms_ai_2_at AS DATETIME(3)) + INTERVAL 600000 MICROSECOND, 2, CAST(@wms_ai_2_at AS DATETIME(3)), CAST(@wms_ai_2_at AS DATETIME(3)) + INTERVAL 600000 MICROSECOND),
    ROW(3, 'ca25b87b-bbd7-4efb-a747-2581c008aa03', 5, 6, 'interrupted', CAST(@wms_ai_3_at AS DATETIME(3)),
        CAST(@wms_ai_3_at AS DATETIME(3)) + INTERVAL 430000 MICROSECOND, 3, CAST(@wms_ai_3_at AS DATETIME(3)), CAST(@wms_ai_3_at AS DATETIME(3)) + INTERVAL 430000 MICROSECOND)
) AS demo
WHERE @wms_load_demo;

INSERT INTO ai_execution_step (
    id, message_id, step_order, event_type, agent_name, tool_name, tool_args, result_summary, duration_ms, status,
    created_by, created_at, updated_at
)
SELECT demo.*
FROM (VALUES
    ROW(1, 2, 1, 'dispatch', 'inventory_agent', NULL, NULL, '查询中央仓充电器可用量', NULL, 'success', 1,
        CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 10000 MICROSECOND, CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 10000 MICROSECOND),
    ROW(2, 2, 2, 'agent_start', 'inventory_agent', NULL, NULL, NULL, NULL, 'running', 1,
        CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 20000 MICROSECOND, CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 20000 MICROSECOND),
    ROW(3, 2, 3, 'tool_start', 'inventory_agent', 'query_realtime_inventory',
        JSON_OBJECT('warehouse', '中央仓', 'keyword', '充电器'), NULL, NULL, 'running', 1,
        CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 40000 MICROSECOND, CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 40000 MICROSECOND),
    ROW(4, 2, 3, 'tool_end', 'inventory_agent', 'query_realtime_inventory', NULL, '命中 2 条库存记录，可用量分别为 20 件和 55 件', 142,
        'success', 1, CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 182000 MICROSECOND, CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 182000 MICROSECOND),
    ROW(5, 2, 4, 'agent_end', 'inventory_agent', NULL, NULL, '命中 2 条库存记录，可用量分别为 20 件和 55 件', 202, 'success', 1,
        CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 222000 MICROSECOND, CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 222000 MICROSECOND),
    ROW(6, 2, 5, 'dispatch', 'alert_agent', NULL, NULL, '核对补货缺口和关联供应商', NULL, 'success', 1,
        CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 290000 MICROSECOND, CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 290000 MICROSECOND),
    ROW(7, 2, 6, 'agent_start', 'alert_agent', NULL, NULL, NULL, NULL, 'running', 1,
        CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 300000 MICROSECOND, CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 300000 MICROSECOND),
    ROW(8, 2, 7, 'tool_start', 'alert_agent', 'suggest_replenishment',
        JSON_OBJECT('warehouse', '中央仓', 'keyword', '充电器'), NULL, NULL, 'running', 1,
        CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 320000 MICROSECOND, CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 320000 MICROSECOND),
    ROW(9, 2, 7, 'tool_end', 'alert_agent', 'suggest_replenishment', NULL, '20W 充电器低于安全下限 10 件，供应商华源电子', 164,
        'success', 1, CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 484000 MICROSECOND, CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 484000 MICROSECOND),
    ROW(10, 2, 8, 'agent_end', 'alert_agent', NULL, NULL, '20W 充电器低于安全下限 10 件，供应商华源电子', 224, 'success', 1,
        CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 524000 MICROSECOND, CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 524000 MICROSECOND),
    ROW(11, 2, 9, 'summarize', 'main_agent', NULL, NULL, '汇总为 Markdown 库存和补货表格', 400, 'success', 1,
        CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 950000 MICROSECOND, CAST(@wms_ai_1_at AS DATETIME(3)) + INTERVAL 950000 MICROSECOND),
    ROW(12, 4, 1, 'dispatch', 'order_agent', NULL, NULL, '核对库存并代建出库草稿', NULL, 'success', 2,
        CAST(@wms_ai_2_at AS DATETIME(3)) + INTERVAL 15000 MICROSECOND, CAST(@wms_ai_2_at AS DATETIME(3)) + INTERVAL 15000 MICROSECOND),
    ROW(13, 4, 2, 'agent_start', 'order_agent', NULL, NULL, NULL, NULL, 'running', 2,
        CAST(@wms_ai_2_at AS DATETIME(3)) + INTERVAL 25000 MICROSECOND, CAST(@wms_ai_2_at AS DATETIME(3)) + INTERVAL 25000 MICROSECOND),
    ROW(14, 4, 3, 'tool_start', 'order_agent', 'create_outbound_draft',
        JSON_OBJECT(
            'warehouse_id', 1, 'customer_id', 1, 'request_key', 'demo-outbound-20w-b001',
            'items', JSON_ARRAY(JSON_OBJECT('location_id', 102, 'product_id', 1, 'batch_no', 'B001', 'quantity', 10))
        ),
        NULL, NULL, 'running', 2, CAST(@wms_ai_2_at AS DATETIME(3)) + INTERVAL 45000 MICROSECOND, CAST(@wms_ai_2_at AS DATETIME(3)) + INTERVAL 45000 MICROSECOND),
    ROW(15, 4, 3, 'tool_end', 'order_agent', 'create_outbound_draft', NULL,
        CONCAT('出库草稿 ', @wms_out_draft_no, ' 已保存，库存可用量 20 件'), 245, 'success', 2,
        CAST(@wms_ai_2_at AS DATETIME(3)) + INTERVAL 290000 MICROSECOND, CAST(@wms_ai_2_at AS DATETIME(3)) + INTERVAL 290000 MICROSECOND),
    ROW(16, 4, 4, 'agent_end', 'order_agent', NULL, NULL, CONCAT('出库草稿 ', @wms_out_draft_no, ' 已保存，库存可用量 20 件'), 305,
        'success', 2, CAST(@wms_ai_2_at AS DATETIME(3)) + INTERVAL 330000 MICROSECOND, CAST(@wms_ai_2_at AS DATETIME(3)) + INTERVAL 330000 MICROSECOND),
    ROW(17, 4, 5, 'summarize', 'main_agent', NULL, NULL, '返回草稿编号与当前可用量', 200, 'success', 2,
        CAST(@wms_ai_2_at AS DATETIME(3)) + INTERVAL 600000 MICROSECOND, CAST(@wms_ai_2_at AS DATETIME(3)) + INTERVAL 600000 MICROSECOND),
    ROW(18, 6, 1, 'dispatch', 'alert_agent', NULL, NULL, '查询中央仓 30 天内临期批次', NULL, 'success', 3,
        CAST(@wms_ai_3_at AS DATETIME(3)) + INTERVAL 10000 MICROSECOND, CAST(@wms_ai_3_at AS DATETIME(3)) + INTERVAL 10000 MICROSECOND),
    ROW(19, 6, 2, 'agent_start', 'alert_agent', NULL, NULL, NULL, NULL, 'running', 3,
        CAST(@wms_ai_3_at AS DATETIME(3)) + INTERVAL 20000 MICROSECOND, CAST(@wms_ai_3_at AS DATETIME(3)) + INTERVAL 20000 MICROSECOND),
    ROW(20, 6, 3, 'tool_start', 'alert_agent', 'query_expiring_alerts', JSON_OBJECT('warehouse', '中央仓', 'days', 30),
        NULL, NULL, 'running', 3, CAST(@wms_ai_3_at AS DATETIME(3)) + INTERVAL 40000 MICROSECOND, CAST(@wms_ai_3_at AS DATETIME(3)) + INTERVAL 40000 MICROSECOND),
    ROW(21, 6, 3, 'tool_end', 'alert_agent', 'query_expiring_alerts', NULL, '命中 W001 批次，库存 24 包，距到期 12 天', 185,
        'success', 3, CAST(@wms_ai_3_at AS DATETIME(3)) + INTERVAL 225000 MICROSECOND, CAST(@wms_ai_3_at AS DATETIME(3)) + INTERVAL 225000 MICROSECOND),
    ROW(22, 6, 4, 'agent_end', 'alert_agent', NULL, NULL, '命中 W001 批次，库存 24 包，距到期 12 天', 245, 'success', 3,
        CAST(@wms_ai_3_at AS DATETIME(3)) + INTERVAL 265000 MICROSECOND, CAST(@wms_ai_3_at AS DATETIME(3)) + INTERVAL 265000 MICROSECOND),
    ROW(23, 6, 5, 'summarize', 'main_agent', NULL, NULL, '流式输出已中断，保存已生成内容', 130, 'error', 3,
        CAST(@wms_ai_3_at AS DATETIME(3)) + INTERVAL 430000 MICROSECOND, CAST(@wms_ai_3_at AS DATETIME(3)) + INTERVAL 430000 MICROSECOND)
) AS demo
WHERE @wms_load_demo;

COMMIT;

DO IF(@wms_seed_lock = 1, RELEASE_LOCK('wms_db.seed_demo'), NULL);
SET time_zone = @wms_previous_time_zone;

SELECT CASE
    WHEN @wms_load_demo THEN 'wms_db 初始化完成，已导入演示数据'
    WHEN @wms_seed_enabled = 0 THEN 'wms_db 建表完成，已选择仅建表'
    WHEN COALESCE(@wms_seed_lock, 0) <> 1 THEN 'wms_db 建表完成，未取得演示数据初始化锁'
    ELSE 'wms_db 已有数据，保留现有记录'
END AS initialization_result;
