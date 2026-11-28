package com.yinhui.springboot.wms.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yinhui.springboot.wms.dto.MasterViews;
import com.yinhui.springboot.wms.dto.OrderViews;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 跨表查询。写操作仍由各实体 Mapper 完成，这里只负责列表、详情和统计口径。
 */
public interface WmsQueryMapper {

    String INBOUND_HEADER = """
            SELECT o.id, o.order_no, o.warehouse_id, w.name AS warehouse_name,
                   o.supplier_id, s.name AS supplier_name, o.source, o.status, o.remark,
                   o.submitted_at, o.reviewed_by, ru.nickname AS reviewer_name, o.reviewed_at,
                   o.reject_reason, o.posted_at, o.created_at, o.created_by, cu.nickname AS creator_name
            FROM wms_inbound_order o
            JOIN wms_warehouse w ON w.id = o.warehouse_id
            LEFT JOIN wms_supplier s ON s.id = o.supplier_id
            LEFT JOIN sys_user ru ON ru.id = o.reviewed_by
            LEFT JOIN sys_user cu ON cu.id = o.created_by
            """;

    @Select("""
            <script>
            SELECT o.id, o.order_no, o.warehouse_id, w.name AS warehouse_name,
                   o.supplier_id, s.name AS supplier_name, o.source, o.status, o.remark,
                   o.submitted_at, o.reviewed_by, ru.nickname AS reviewer_name, o.reviewed_at,
                   o.reject_reason, o.posted_at, o.created_at, o.created_by, cu.nickname AS creator_name,
                   COALESCE(t.item_count, 0) AS item_count,
                   COALESCE(t.total_quantity, 0) AS total_quantity,
                   COALESCE(t.total_amount, 0) AS total_amount
            FROM wms_inbound_order o
            JOIN wms_warehouse w ON w.id = o.warehouse_id
            LEFT JOIN wms_supplier s ON s.id = o.supplier_id
            LEFT JOIN sys_user ru ON ru.id = o.reviewed_by
            LEFT JOIN sys_user cu ON cu.id = o.created_by
            LEFT JOIN (
                SELECT order_id, COUNT(*) AS item_count, SUM(quantity) AS total_quantity,
                       SUM(quantity * unit_price) AS total_amount
                FROM wms_inbound_item WHERE is_deleted = 0 GROUP BY order_id
            ) t ON t.order_id = o.id
            WHERE o.is_deleted = 0
            <if test="status != null"> AND o.status = #{status}</if>
            <if test="warehouseId != null"> AND o.warehouse_id = #{warehouseId}</if>
            <if test="source != null and source != ''"> AND o.source = #{source}</if>
            <if test="keyword != null and keyword != ''">
                AND (o.order_no LIKE CONCAT('%', #{keyword}, '%')
                     OR s.name LIKE CONCAT('%', #{keyword}, '%')
                     OR w.name LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="dateFrom != null"> AND o.created_at &gt;= #{dateFrom}</if>
            <if test="dateTo != null"> AND o.created_at &lt; #{dateTo}</if>
            ORDER BY o.created_at DESC, o.id DESC
            </script>
            """)
    IPage<OrderViews.InboundOrderRow> selectInboundOrders(IPage<?> page,
            @Param("status") Integer status, @Param("warehouseId") Long warehouseId,
            @Param("source") String source, @Param("keyword") String keyword,
            @Param("dateFrom") LocalDateTime dateFrom, @Param("dateTo") LocalDateTime dateTo);

    @Select(INBOUND_HEADER + " WHERE o.id = #{id} AND o.is_deleted = 0")
    OrderViews.InboundOrderRow selectInboundOrder(@Param("id") long id);

    @Select("""
            SELECT i.id, i.product_id, p.sku_code, p.name AS product_name, p.unit,
                   i.location_id, l.code AS location_code, i.batch_no, i.production_date, i.expire_date,
                   i.quantity, i.unit_price, (i.quantity * i.unit_price) AS amount
            FROM wms_inbound_item i
            JOIN wms_product p ON p.id = i.product_id
            JOIN wms_location l ON l.id = i.location_id
            WHERE i.order_id = #{orderId} AND i.is_deleted = 0
            ORDER BY i.id
            """)
    List<OrderViews.InboundItemRow> selectInboundItems(@Param("orderId") long orderId);

    @Select("""
            <script>
            SELECT o.id, o.order_no, o.warehouse_id, w.name AS warehouse_name,
                   o.customer_id, c.name AS customer_name, o.source, o.status, o.remark,
                   o.submitted_at, o.reviewed_by, ru.nickname AS reviewer_name, o.reviewed_at,
                   o.reject_reason, o.posted_at, o.created_at, o.created_by, cu.nickname AS creator_name,
                   COALESCE(t.item_count, 0) AS item_count,
                   COALESCE(t.total_quantity, 0) AS total_quantity,
                   COALESCE(t.total_amount, 0) AS total_amount
            FROM wms_outbound_order o
            JOIN wms_warehouse w ON w.id = o.warehouse_id
            LEFT JOIN wms_customer c ON c.id = o.customer_id
            LEFT JOIN sys_user ru ON ru.id = o.reviewed_by
            LEFT JOIN sys_user cu ON cu.id = o.created_by
            LEFT JOIN (
                SELECT order_id, COUNT(*) AS item_count, SUM(quantity) AS total_quantity,
                       SUM(quantity * unit_price) AS total_amount
                FROM wms_outbound_item WHERE is_deleted = 0 GROUP BY order_id
            ) t ON t.order_id = o.id
            WHERE o.is_deleted = 0
            <if test="status != null"> AND o.status = #{status}</if>
            <if test="warehouseId != null"> AND o.warehouse_id = #{warehouseId}</if>
            <if test="source != null and source != ''"> AND o.source = #{source}</if>
            <if test="keyword != null and keyword != ''">
                AND (o.order_no LIKE CONCAT('%', #{keyword}, '%')
                     OR c.name LIKE CONCAT('%', #{keyword}, '%')
                     OR w.name LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="dateFrom != null"> AND o.created_at &gt;= #{dateFrom}</if>
            <if test="dateTo != null"> AND o.created_at &lt; #{dateTo}</if>
            ORDER BY o.created_at DESC, o.id DESC
            </script>
            """)
    IPage<OrderViews.OutboundOrderRow> selectOutboundOrders(IPage<?> page,
            @Param("status") Integer status, @Param("warehouseId") Long warehouseId,
            @Param("source") String source, @Param("keyword") String keyword,
            @Param("dateFrom") LocalDateTime dateFrom, @Param("dateTo") LocalDateTime dateTo);

    @Select("""
            SELECT o.id, o.order_no, o.warehouse_id, w.name AS warehouse_name,
                   o.customer_id, c.name AS customer_name, o.source, o.status, o.remark,
                   o.submitted_at, o.reviewed_by, ru.nickname AS reviewer_name, o.reviewed_at,
                   o.reject_reason, o.posted_at, o.created_at, o.created_by, cu.nickname AS creator_name
            FROM wms_outbound_order o
            JOIN wms_warehouse w ON w.id = o.warehouse_id
            LEFT JOIN wms_customer c ON c.id = o.customer_id
            LEFT JOIN sys_user ru ON ru.id = o.reviewed_by
            LEFT JOIN sys_user cu ON cu.id = o.created_by
            WHERE o.id = #{id} AND o.is_deleted = 0
            """)
    OrderViews.OutboundOrderRow selectOutboundOrder(@Param("id") long id);

    @Select("""
            SELECT i.id, i.product_id, p.sku_code, p.name AS product_name, p.unit,
                   i.location_id, l.code AS location_code, i.batch_no,
                   i.quantity, i.unit_price, (i.quantity * i.unit_price) AS amount
            FROM wms_outbound_item i
            JOIN wms_product p ON p.id = i.product_id
            JOIN wms_location l ON l.id = i.location_id
            WHERE i.order_id = #{orderId} AND i.is_deleted = 0
            ORDER BY i.id
            """)
    List<OrderViews.OutboundItemRow> selectOutboundItems(@Param("orderId") long orderId);

    @Select("""
            <script>
            SELECT o.id, o.order_no, o.warehouse_id, w.name AS warehouse_name, o.status, o.remark,
                   o.submitted_at, o.reviewed_by, ru.nickname AS reviewer_name, o.reviewed_at,
                   o.reject_reason, o.posted_at, o.created_at, o.created_by, cu.nickname AS creator_name,
                   COALESCE(t.item_count, 0) AS item_count, COALESCE(t.total_quantity, 0) AS total_quantity
            FROM wms_freeze_order o
            JOIN wms_warehouse w ON w.id = o.warehouse_id
            LEFT JOIN sys_user ru ON ru.id = o.reviewed_by
            LEFT JOIN sys_user cu ON cu.id = o.created_by
            LEFT JOIN (
                SELECT order_id, COUNT(*) AS item_count, SUM(quantity) AS total_quantity
                FROM wms_freeze_item WHERE is_deleted = 0 GROUP BY order_id
            ) t ON t.order_id = o.id
            WHERE o.is_deleted = 0
            <if test="status != null"> AND o.status = #{status}</if>
            <if test="warehouseId != null"> AND o.warehouse_id = #{warehouseId}</if>
            <if test="keyword != null and keyword != ''"> AND o.order_no LIKE CONCAT('%', #{keyword}, '%')</if>
            <if test="dateFrom != null"> AND o.created_at &gt;= #{dateFrom}</if>
            <if test="dateTo != null"> AND o.created_at &lt; #{dateTo}</if>
            ORDER BY o.created_at DESC, o.id DESC
            </script>
            """)
    IPage<OrderViews.FreezeOrderRow> selectFreezeOrders(IPage<?> page,
            @Param("status") Integer status, @Param("warehouseId") Long warehouseId,
            @Param("keyword") String keyword,
            @Param("dateFrom") LocalDateTime dateFrom, @Param("dateTo") LocalDateTime dateTo);

    @Select("""
            SELECT o.id, o.order_no, o.warehouse_id, w.name AS warehouse_name, o.status, o.remark,
                   o.submitted_at, o.reviewed_by, ru.nickname AS reviewer_name, o.reviewed_at,
                   o.reject_reason, o.posted_at, o.created_at, o.created_by, cu.nickname AS creator_name
            FROM wms_freeze_order o
            JOIN wms_warehouse w ON w.id = o.warehouse_id
            LEFT JOIN sys_user ru ON ru.id = o.reviewed_by
            LEFT JOIN sys_user cu ON cu.id = o.created_by
            WHERE o.id = #{id} AND o.is_deleted = 0
            """)
    OrderViews.FreezeOrderRow selectFreezeOrder(@Param("id") long id);

    @Select("""
            SELECT i.id, i.product_id, p.sku_code, p.name AS product_name, p.unit,
                   i.location_id, l.code AS location_code, i.batch_no, i.op_type, i.quantity, i.reason
            FROM wms_freeze_item i
            JOIN wms_product p ON p.id = i.product_id
            JOIN wms_location l ON l.id = i.location_id
            WHERE i.order_id = #{orderId} AND i.is_deleted = 0
            ORDER BY i.id
            """)
    List<OrderViews.FreezeItemRow> selectFreezeItems(@Param("orderId") long orderId);

    @Select("""
            <script>
            SELECT o.id, o.order_no, o.from_warehouse_id, fw.name AS from_warehouse_name,
                   o.to_warehouse_id, tw.name AS to_warehouse_name, o.status, o.remark,
                   o.submitted_at, o.reviewed_by, ru.nickname AS reviewer_name, o.reviewed_at,
                   o.reject_reason, o.posted_at, o.created_at, o.created_by, cu.nickname AS creator_name,
                   COALESCE(t.item_count, 0) AS item_count, COALESCE(t.total_quantity, 0) AS total_quantity
            FROM wms_transfer_order o
            JOIN wms_warehouse fw ON fw.id = o.from_warehouse_id
            JOIN wms_warehouse tw ON tw.id = o.to_warehouse_id
            LEFT JOIN sys_user ru ON ru.id = o.reviewed_by
            LEFT JOIN sys_user cu ON cu.id = o.created_by
            LEFT JOIN (
                SELECT order_id, COUNT(*) AS item_count, SUM(quantity) AS total_quantity
                FROM wms_transfer_item WHERE is_deleted = 0 GROUP BY order_id
            ) t ON t.order_id = o.id
            WHERE o.is_deleted = 0
            <if test="status != null"> AND o.status = #{status}</if>
            <if test="warehouseId != null"> AND (o.from_warehouse_id = #{warehouseId} OR o.to_warehouse_id = #{warehouseId})</if>
            <if test="keyword != null and keyword != ''"> AND o.order_no LIKE CONCAT('%', #{keyword}, '%')</if>
            <if test="dateFrom != null"> AND o.created_at &gt;= #{dateFrom}</if>
            <if test="dateTo != null"> AND o.created_at &lt; #{dateTo}</if>
            ORDER BY o.created_at DESC, o.id DESC
            </script>
            """)
    IPage<OrderViews.TransferOrderRow> selectTransferOrders(IPage<?> page,
            @Param("status") Integer status, @Param("warehouseId") Long warehouseId,
            @Param("keyword") String keyword,
            @Param("dateFrom") LocalDateTime dateFrom, @Param("dateTo") LocalDateTime dateTo);

    @Select("""
            SELECT o.id, o.order_no, o.from_warehouse_id, fw.name AS from_warehouse_name,
                   o.to_warehouse_id, tw.name AS to_warehouse_name, o.status, o.remark,
                   o.submitted_at, o.reviewed_by, ru.nickname AS reviewer_name, o.reviewed_at,
                   o.reject_reason, o.posted_at, o.created_at, o.created_by, cu.nickname AS creator_name
            FROM wms_transfer_order o
            JOIN wms_warehouse fw ON fw.id = o.from_warehouse_id
            JOIN wms_warehouse tw ON tw.id = o.to_warehouse_id
            LEFT JOIN sys_user ru ON ru.id = o.reviewed_by
            LEFT JOIN sys_user cu ON cu.id = o.created_by
            WHERE o.id = #{id} AND o.is_deleted = 0
            """)
    OrderViews.TransferOrderRow selectTransferOrder(@Param("id") long id);

    @Select("""
            SELECT i.id, i.product_id, p.sku_code, p.name AS product_name, p.unit,
                   i.from_location_id, fl.code AS from_location_code,
                   i.to_location_id, tl.code AS to_location_code,
                   i.batch_no, i.quantity
            FROM wms_transfer_item i
            JOIN wms_product p ON p.id = i.product_id
            JOIN wms_location fl ON fl.id = i.from_location_id
            JOIN wms_location tl ON tl.id = i.to_location_id
            WHERE i.order_id = #{orderId} AND i.is_deleted = 0
            ORDER BY i.id
            """)
    List<OrderViews.TransferItemRow> selectTransferItems(@Param("orderId") long orderId);

    @Select("""
            <script>
            SELECT o.id, o.order_no, o.warehouse_id, w.name AS warehouse_name, o.status, o.remark,
                   o.submitted_at, o.reviewed_by, ru.nickname AS reviewer_name, o.reviewed_at,
                   o.reject_reason, o.posted_at, o.created_at, o.created_by, cu.nickname AS creator_name,
                   COALESCE(t.item_count, 0) AS item_count,
                   COALESCE(t.counted_count, 0) AS counted_count,
                   COALESCE(t.diff_count, 0) AS diff_count,
                   COALESCE(t.total_diff, 0) AS total_diff
            FROM wms_stocktake_order o
            JOIN wms_warehouse w ON w.id = o.warehouse_id
            LEFT JOIN sys_user ru ON ru.id = o.reviewed_by
            LEFT JOIN sys_user cu ON cu.id = o.created_by
            LEFT JOIN (
                SELECT order_id, COUNT(*) AS item_count,
                       SUM(CASE WHEN actual_qty IS NOT NULL THEN 1 ELSE 0 END) AS counted_count,
                       SUM(CASE WHEN actual_qty IS NOT NULL AND actual_qty &lt;&gt; book_qty THEN 1 ELSE 0 END) AS diff_count,
                       SUM(CASE WHEN actual_qty IS NOT NULL THEN ABS(actual_qty - book_qty) ELSE 0 END) AS total_diff
                FROM wms_stocktake_item WHERE is_deleted = 0 GROUP BY order_id
            ) t ON t.order_id = o.id
            WHERE o.is_deleted = 0
            <if test="status != null"> AND o.status = #{status}</if>
            <if test="warehouseId != null"> AND o.warehouse_id = #{warehouseId}</if>
            <if test="keyword != null and keyword != ''"> AND o.order_no LIKE CONCAT('%', #{keyword}, '%')</if>
            <if test="dateFrom != null"> AND o.created_at &gt;= #{dateFrom}</if>
            <if test="dateTo != null"> AND o.created_at &lt; #{dateTo}</if>
            ORDER BY o.created_at DESC, o.id DESC
            </script>
            """)
    IPage<OrderViews.StocktakeOrderRow> selectStocktakeOrders(IPage<?> page,
            @Param("status") Integer status, @Param("warehouseId") Long warehouseId,
            @Param("keyword") String keyword,
            @Param("dateFrom") LocalDateTime dateFrom, @Param("dateTo") LocalDateTime dateTo);

    @Select("""
            SELECT o.id, o.order_no, o.warehouse_id, w.name AS warehouse_name, o.status, o.remark,
                   o.submitted_at, o.reviewed_by, ru.nickname AS reviewer_name, o.reviewed_at,
                   o.reject_reason, o.posted_at, o.created_at, o.created_by, cu.nickname AS creator_name
            FROM wms_stocktake_order o
            JOIN wms_warehouse w ON w.id = o.warehouse_id
            LEFT JOIN sys_user ru ON ru.id = o.reviewed_by
            LEFT JOIN sys_user cu ON cu.id = o.created_by
            WHERE o.id = #{id} AND o.is_deleted = 0
            """)
    OrderViews.StocktakeOrderRow selectStocktakeOrder(@Param("id") long id);

    @Select("""
            SELECT i.id, i.product_id, p.sku_code, p.name AS product_name, p.unit,
                   i.location_id, l.code AS location_code, i.batch_no,
                   i.book_qty, i.actual_qty, i.diff_qty, i.inventory_version
            FROM wms_stocktake_item i
            JOIN wms_product p ON p.id = i.product_id
            JOIN wms_location l ON l.id = i.location_id
            WHERE i.order_id = #{orderId} AND i.is_deleted = 0
            ORDER BY i.id
            """)
    List<OrderViews.StocktakeItemRow> selectStocktakeItems(@Param("orderId") long orderId);

    @Select("""
            <script>
            SELECT o.id, o.order_no, o.warehouse_id, w.name AS warehouse_name,
                   o.stocktake_order_id, so.order_no AS stocktake_order_no, o.status, o.remark,
                   o.submitted_at, o.reviewed_by, ru.nickname AS reviewer_name, o.reviewed_at,
                   o.reject_reason, o.posted_at, o.created_at, o.created_by, cu.nickname AS creator_name,
                   COALESCE(t.item_count, 0) AS item_count, COALESCE(t.total_quantity, 0) AS total_quantity
            FROM wms_adjust_order o
            JOIN wms_warehouse w ON w.id = o.warehouse_id
            LEFT JOIN wms_stocktake_order so ON so.id = o.stocktake_order_id
            LEFT JOIN sys_user ru ON ru.id = o.reviewed_by
            LEFT JOIN sys_user cu ON cu.id = o.created_by
            LEFT JOIN (
                SELECT order_id, COUNT(*) AS item_count, SUM(adjust_qty) AS total_quantity
                FROM wms_adjust_item WHERE is_deleted = 0 GROUP BY order_id
            ) t ON t.order_id = o.id
            WHERE o.is_deleted = 0
            <if test="status != null"> AND o.status = #{status}</if>
            <if test="warehouseId != null"> AND o.warehouse_id = #{warehouseId}</if>
            <if test="keyword != null and keyword != ''"> AND o.order_no LIKE CONCAT('%', #{keyword}, '%')</if>
            <if test="dateFrom != null"> AND o.created_at &gt;= #{dateFrom}</if>
            <if test="dateTo != null"> AND o.created_at &lt; #{dateTo}</if>
            ORDER BY o.created_at DESC, o.id DESC
            </script>
            """)
    IPage<OrderViews.AdjustOrderRow> selectAdjustOrders(IPage<?> page,
            @Param("status") Integer status, @Param("warehouseId") Long warehouseId,
            @Param("keyword") String keyword,
            @Param("dateFrom") LocalDateTime dateFrom, @Param("dateTo") LocalDateTime dateTo);

    @Select("""
            SELECT o.id, o.order_no, o.warehouse_id, w.name AS warehouse_name,
                   o.stocktake_order_id, so.order_no AS stocktake_order_no, o.status, o.remark,
                   o.submitted_at, o.reviewed_by, ru.nickname AS reviewer_name, o.reviewed_at,
                   o.reject_reason, o.posted_at, o.created_at, o.created_by, cu.nickname AS creator_name
            FROM wms_adjust_order o
            JOIN wms_warehouse w ON w.id = o.warehouse_id
            LEFT JOIN wms_stocktake_order so ON so.id = o.stocktake_order_id
            LEFT JOIN sys_user ru ON ru.id = o.reviewed_by
            LEFT JOIN sys_user cu ON cu.id = o.created_by
            WHERE o.id = #{id} AND o.is_deleted = 0
            """)
    OrderViews.AdjustOrderRow selectAdjustOrder(@Param("id") long id);

    @Select("""
            SELECT i.id, i.product_id, p.sku_code, p.name AS product_name, p.unit,
                   i.location_id, l.code AS location_code, i.batch_no,
                   i.adjust_type, i.adjust_qty, i.reason
            FROM wms_adjust_item i
            JOIN wms_product p ON p.id = i.product_id
            JOIN wms_location l ON l.id = i.location_id
            WHERE i.order_id = #{orderId} AND i.is_deleted = 0
            ORDER BY i.id
            """)
    List<OrderViews.AdjustItemRow> selectAdjustItems(@Param("orderId") long orderId);

    @Select("""
            <script>
            SELECT a.id, a.warehouse_id, w.name AS warehouse_name, a.code, a.name, a.area_type
            FROM wms_area a
            JOIN wms_warehouse w ON w.id = a.warehouse_id
            WHERE a.is_deleted = 0
            <if test="warehouseId != null"> AND a.warehouse_id = #{warehouseId}</if>
            <if test="keyword != null and keyword != ''">
                AND (a.name LIKE CONCAT('%', #{keyword}, '%') OR a.code LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            ORDER BY a.warehouse_id, a.code
            </script>
            """)
    List<MasterViews.AreaRow> selectAreas(@Param("warehouseId") Long warehouseId,
            @Param("keyword") String keyword);

    @Select("""
            <script>
            SELECT l.id, l.warehouse_id, w.name AS warehouse_name, l.area_id, a.name AS area_name,
                   l.code, l.max_capacity, l.status
            FROM wms_location l
            JOIN wms_warehouse w ON w.id = l.warehouse_id
            JOIN wms_area a ON a.id = l.area_id
            WHERE l.is_deleted = 0
            <if test="warehouseId != null"> AND l.warehouse_id = #{warehouseId}</if>
            <if test="areaId != null"> AND l.area_id = #{areaId}</if>
            <if test="status != null"> AND l.status = #{status}</if>
            <if test="keyword != null and keyword != ''"> AND l.code LIKE CONCAT('%', #{keyword}, '%')</if>
            ORDER BY l.warehouse_id, l.area_id, l.code
            </script>
            """)
    IPage<MasterViews.LocationRow> selectLocations(IPage<?> page, @Param("warehouseId") Long warehouseId,
            @Param("areaId") Long areaId, @Param("status") Integer status,
            @Param("keyword") String keyword);

    @Select("""
            <script>
            SELECT p.id, p.sku_code, p.name, p.category_id, c.name AS category_name, p.spec, p.unit,
                   p.batch_managed, p.min_stock, p.max_stock, p.shelf_life_days,
                   p.default_supplier_id, s.name AS supplier_name, p.reference_price, p.updated_at
            FROM wms_product p
            LEFT JOIN wms_category c ON c.id = p.category_id
            LEFT JOIN wms_supplier s ON s.id = p.default_supplier_id
            WHERE p.is_deleted = 0
            <if test="categoryId != null"> AND p.category_id = #{categoryId}</if>
            <if test="batchManaged != null"> AND p.batch_managed = #{batchManaged}</if>
            <if test="keyword != null and keyword != ''">
                AND (p.name LIKE CONCAT('%', #{keyword}, '%') OR p.sku_code LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            ORDER BY p.id
            </script>
            """)
    IPage<MasterViews.ProductRow> selectProducts(IPage<?> page, @Param("categoryId") Long categoryId,
            @Param("batchManaged") Integer batchManaged, @Param("keyword") String keyword);

    @Select("""
            SELECT p.id, p.sku_code, p.name, p.category_id, c.name AS category_name, p.spec, p.unit,
                   p.batch_managed, p.min_stock, p.max_stock, p.shelf_life_days,
                   p.default_supplier_id, s.name AS supplier_name, p.reference_price, p.updated_at
            FROM wms_product p
            LEFT JOIN wms_category c ON c.id = p.category_id
            LEFT JOIN wms_supplier s ON s.id = p.default_supplier_id
            WHERE p.id = #{id} AND p.is_deleted = 0
            """)
    MasterViews.ProductRow selectProduct(@Param("id") long id);

    @Select("""
            <script>
            SELECT inv.id, inv.warehouse_id, w.name AS warehouse_name,
                   inv.location_id, l.code AS location_code,
                   inv.product_id, p.sku_code, p.name AS product_name, p.unit,
                   inv.batch_no, inv.production_date, inv.expire_date,
                   inv.quantity, inv.frozen_quantity, inv.available_quantity, inv.version, inv.updated_at
            FROM wms_inventory inv
            JOIN wms_warehouse w ON w.id = inv.warehouse_id
            JOIN wms_location l ON l.id = inv.location_id
            JOIN wms_product p ON p.id = inv.product_id
            WHERE inv.is_deleted = 0
            <if test="warehouseId != null"> AND inv.warehouse_id = #{warehouseId}</if>
            <if test="locationId != null"> AND inv.location_id = #{locationId}</if>
            <if test="productId != null"> AND inv.product_id = #{productId}</if>
            <if test="batchNo != null and batchNo != ''"> AND inv.batch_no = #{batchNo}</if>
            <if test="keyword != null and keyword != ''">
                AND (p.name LIKE CONCAT('%', #{keyword}, '%') OR p.sku_code LIKE CONCAT('%', #{keyword}, '%')
                     OR inv.batch_no LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="onlyAvailable"> AND inv.available_quantity &gt; 0</if>
            ORDER BY inv.warehouse_id, inv.location_id, inv.product_id, inv.batch_no
            </script>
            """)
    IPage<MasterViews.InventoryRow> selectInventory(IPage<?> page,
            @Param("warehouseId") Long warehouseId, @Param("locationId") Long locationId,
            @Param("productId") Long productId, @Param("batchNo") String batchNo,
            @Param("keyword") String keyword, @Param("onlyAvailable") boolean onlyAvailable);

    @Select("""
            SELECT inv.id, inv.warehouse_id, w.name AS warehouse_name,
                   inv.location_id, l.code AS location_code,
                   inv.product_id, p.sku_code, p.name AS product_name, p.unit,
                   inv.batch_no, inv.production_date, inv.expire_date,
                   inv.quantity, inv.frozen_quantity, inv.available_quantity, inv.version, inv.updated_at
            FROM wms_inventory inv
            JOIN wms_warehouse w ON w.id = inv.warehouse_id
            JOIN wms_location l ON l.id = inv.location_id
            JOIN wms_product p ON p.id = inv.product_id
            WHERE inv.id = #{id} AND inv.is_deleted = 0
            """)
    MasterViews.InventoryRow selectInventoryItem(@Param("id") long id);

    @Select("""
            <script>
            SELECT lg.id, lg.inventory_id, lg.warehouse_id, w.name AS warehouse_name,
                   lg.location_id, l.code AS location_code,
                   lg.product_id, p.sku_code, p.name AS product_name, p.unit,
                   lg.batch_no, lg.biz_type, lg.order_no,
                   lg.before_qty, lg.change_qty, lg.after_qty,
                   lg.before_frozen_qty, lg.change_frozen_qty, lg.after_frozen_qty,
                   lg.operator_id, u.nickname AS operator_name, lg.created_at
            FROM wms_inventory_log lg
            JOIN wms_warehouse w ON w.id = lg.warehouse_id
            JOIN wms_location l ON l.id = lg.location_id
            JOIN wms_product p ON p.id = lg.product_id
            LEFT JOIN sys_user u ON u.id = lg.operator_id
            WHERE lg.is_deleted = 0
            <if test="warehouseId != null"> AND lg.warehouse_id = #{warehouseId}</if>
            <if test="productId != null"> AND lg.product_id = #{productId}</if>
            <if test="bizType != null and bizType != ''"> AND lg.biz_type = #{bizType}</if>
            <if test="orderNo != null and orderNo != ''"> AND lg.order_no = #{orderNo}</if>
            <if test="dateFrom != null"> AND lg.created_at &gt;= #{dateFrom}</if>
            <if test="dateTo != null"> AND lg.created_at &lt; #{dateTo}</if>
            ORDER BY lg.created_at DESC, lg.id DESC
            </script>
            """)
    IPage<MasterViews.InventoryLogRow> selectInventoryLogs(IPage<?> page,
            @Param("warehouseId") Long warehouseId, @Param("productId") Long productId,
            @Param("bizType") String bizType, @Param("orderNo") String orderNo,
            @Param("dateFrom") LocalDateTime dateFrom, @Param("dateTo") LocalDateTime dateTo);

    @Select("""
            <script>
            SELECT w.id AS warehouse_id, w.name AS warehouse_name,
                   p.id AS product_id, p.sku_code, p.name AS product_name, p.unit,
                   COALESCE(SUM(inv.quantity), 0) AS quantity,
                   COALESCE(SUM(inv.frozen_quantity), 0) AS frozen_quantity,
                   COALESCE(SUM(inv.available_quantity), 0) AS available_quantity,
                   p.min_stock AS min_stock,
                   p.min_stock - COALESCE(SUM(inv.available_quantity), 0) AS shortage,
                   (COUNT(inv.id) = 0) AS no_stock_record
            FROM wms_warehouse w
            CROSS JOIN wms_product p
            LEFT JOIN wms_inventory inv ON inv.warehouse_id = w.id AND inv.product_id = p.id AND inv.is_deleted = 0
            WHERE w.is_deleted = 0 AND w.status = 1 AND p.is_deleted = 0 AND p.min_stock &gt; 0
            <if test="warehouseId != null"> AND w.id = #{warehouseId}</if>
            <if test="keyword != null and keyword != ''">
                AND (p.name LIKE CONCAT('%', #{keyword}, '%') OR p.sku_code LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            GROUP BY w.id, w.name, p.id, p.sku_code, p.name, p.unit, p.min_stock
            HAVING p.min_stock - COALESCE(SUM(inv.available_quantity), 0) &gt; 0
            <if test="!includeMissing"> AND COUNT(inv.id) &gt; 0</if>
            ORDER BY shortage DESC, w.id, p.id
            </script>
            """)
    IPage<MasterViews.LowStockRow> selectLowStock(IPage<?> page, @Param("warehouseId") Long warehouseId,
            @Param("keyword") String keyword, @Param("includeMissing") boolean includeMissing);

    @Select("""
            <script>
            SELECT w.id AS warehouse_id, w.name AS warehouse_name,
                   p.id AS product_id, p.sku_code, p.name AS product_name, p.unit,
                   COALESCE(SUM(inv.quantity), 0) AS quantity,
                   COALESCE(SUM(inv.frozen_quantity), 0) AS frozen_quantity,
                   COALESCE(SUM(inv.available_quantity), 0) AS available_quantity,
                   p.max_stock AS max_stock,
                   COALESCE(SUM(inv.available_quantity), 0) - p.max_stock AS excess
            FROM wms_warehouse w
            CROSS JOIN wms_product p
            LEFT JOIN wms_inventory inv ON inv.warehouse_id = w.id AND inv.product_id = p.id AND inv.is_deleted = 0
            WHERE w.is_deleted = 0 AND w.status = 1 AND p.is_deleted = 0 AND p.max_stock &gt; 0
            <if test="warehouseId != null"> AND w.id = #{warehouseId}</if>
            <if test="keyword != null and keyword != ''">
                AND (p.name LIKE CONCAT('%', #{keyword}, '%') OR p.sku_code LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            GROUP BY w.id, w.name, p.id, p.sku_code, p.name, p.unit, p.max_stock
            HAVING COALESCE(SUM(inv.available_quantity), 0) - p.max_stock &gt; 0
            ORDER BY excess DESC, w.id, p.id
            </script>
            """)
    IPage<MasterViews.OverstockRow> selectOverstock(IPage<?> page, @Param("warehouseId") Long warehouseId,
            @Param("keyword") String keyword);

    @Select("""
            <script>
            SELECT inv.id AS inventory_id, inv.warehouse_id, w.name AS warehouse_name,
                   inv.location_id, l.code AS location_code,
                   inv.product_id, p.sku_code, p.name AS product_name, p.unit,
                   inv.batch_no, inv.expire_date, inv.quantity, inv.frozen_quantity, inv.available_quantity,
                   DATEDIFF(inv.expire_date, CURDATE()) AS days_left
            FROM wms_inventory inv
            JOIN wms_warehouse w ON w.id = inv.warehouse_id
            JOIN wms_location l ON l.id = inv.location_id
            JOIN wms_product p ON p.id = inv.product_id
            WHERE inv.is_deleted = 0 AND inv.expire_date IS NOT NULL AND inv.available_quantity &gt; 0
            <if test="warehouseId != null"> AND inv.warehouse_id = #{warehouseId}</if>
            <choose>
                <when test="scope == 'EXPIRED'"> AND inv.expire_date &lt; #{today}</when>
                <when test="scope == 'ALL'"> AND inv.expire_date &lt;= #{until}</when>
                <otherwise> AND inv.expire_date &gt;= #{today} AND inv.expire_date &lt;= #{until}</otherwise>
            </choose>
            ORDER BY inv.expire_date, inv.id
            </script>
            """)
    IPage<MasterViews.ExpiringRow> selectExpiring(IPage<?> page, @Param("warehouseId") Long warehouseId,
            @Param("scope") String scope, @Param("today") LocalDate today, @Param("until") LocalDate until);

    @Select("""
            <script>
            SELECT
                (SELECT COUNT(*) FROM wms_warehouse WHERE is_deleted = 0 AND status = 1) AS warehouse_count,
                (SELECT COUNT(*) FROM wms_product WHERE is_deleted = 0) AS product_count,
                (SELECT COALESCE(SUM(quantity), 0) FROM wms_inventory WHERE is_deleted = 0) AS total_quantity,
                (SELECT COALESCE(SUM(frozen_quantity), 0) FROM wms_inventory WHERE is_deleted = 0) AS total_frozen_quantity,
                (SELECT COALESCE(SUM(inv.quantity * p.reference_price), 0)
                   FROM wms_inventory inv JOIN wms_product p ON p.id = inv.product_id
                  WHERE inv.is_deleted = 0) AS inventory_value,
                (SELECT COUNT(*) FROM wms_inbound_order WHERE is_deleted = 0 AND status = 1) AS pending_inbound,
                (SELECT COUNT(*) FROM wms_outbound_order WHERE is_deleted = 0 AND status = 1) AS pending_outbound,
                (SELECT COUNT(*) FROM (
                    SELECT w.id AS warehouse_id, p.id AS product_id
                    FROM wms_warehouse w CROSS JOIN wms_product p
                    LEFT JOIN wms_inventory inv ON inv.warehouse_id = w.id AND inv.product_id = p.id AND inv.is_deleted = 0
                    WHERE w.is_deleted = 0 AND w.status = 1 AND p.is_deleted = 0 AND p.min_stock > 0
                    GROUP BY w.id, p.id, p.min_stock
                    HAVING p.min_stock > COALESCE(SUM(inv.available_quantity), 0)
                ) low) AS low_stock_count,
                (SELECT COUNT(*) FROM wms_inventory
                  WHERE is_deleted = 0 AND expire_date IS NOT NULL AND available_quantity > 0
                    AND expire_date &lt;= DATE_ADD(CURDATE(), INTERVAL 30 DAY)) AS expiring_count
            </script>
            """)
    MasterViews.DashboardSummary selectDashboardSummary();
}
