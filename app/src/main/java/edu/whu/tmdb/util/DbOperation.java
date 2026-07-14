package edu.whu.tmdb.util;

import static edu.whu.tmdb.Log.Constants.LOG_BASE_DIR;
import static edu.whu.tmdb.storage.utils.Constant.DATABASE_DIR;
import static edu.whu.tmdb.storage.utils.Constant.SYSTEM_TABLE_DIR;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.whu.tmdb.query.operations.Exception.TMDBException;
import edu.whu.tmdb.query.operations.impl.InsertImpl;
import edu.whu.tmdb.query.operations.utils.MemConnect;
import edu.whu.tmdb.query.operations.utils.SelectResult;
import edu.whu.tmdb.storage.memory.Tuple;
import edu.whu.tmdb.storage.memory.SystemTable.AttributeTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.BiPointerTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.ClassTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.DeputyTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.SwitchingTableItem;

public class DbOperation {
    /**
     * 给定元组查询结果，输出查询表格
     * @param result 查询语句的查询结果
     */
    public static void printResult(SelectResult result) throws TMDBException {//jinx
        // 获取 className 和第一个 classId
        StringBuilder tableHeader = new StringBuilder("|");
        for (int i = 0; i < result.getAttrname().length; i++) {
            tableHeader.append(String.format("%-20s", result.getClassName()[i] + "." + result.getAttrname()[i])).append("|");
        }
        boolean isunion=result.getisUnion();
        // 输出表头，确保没有多余的换行符
        System.out.println(tableHeader.toString());

        String classname = result.getClassName()[0];
        int kk = MemConnect.getClassId(classname);

        // 获取 deputy type (通过 deputyclassid 获取 deputyrule)
        String deputyType = InsertImpl.getdeputyrule(kk,1);

        // 判断 deputyType 是否为 "uniondeputy"
        boolean isUnionDeputy = "uniondeputy".equals(deputyType);
        //System.out.println(deputyType);

        // 创建用于去重的 Set，如果是 "uniondeputy" 需要去重
        Set<String> uniqueResults = new HashSet<>();

        // 输出元组信息
        for (Tuple tuple : result.getTpl().tuplelist) {
            StringBuilder data = new StringBuilder("|");  // 每行数据的开始加上分隔符

            // 遍历 tuple 并拼接数据
            for (int i = 0; i < tuple.tuple.length; i++) {
                if (tuple.tuple[i] != null) {
                    data.append(String.format("%-20s", tuple.tuple[i].toString()));
                } else {
                    data.append(String.format("%-20s", "NULL"));  // 处理null值
                }
                data.append("|");  // 每列数据后面加上分隔符
            }

            // 如果是 "uniondeputy"，对结果进行去重
            if (isUnionDeputy||isunion) {

                String rowString = data.toString();
                if (uniqueResults.add(rowString)) {
                    System.out.println(rowString);
                }
            } else {
                // 否则直接输出
                System.out.println(data.toString());
            }
        }
    }

    /**
     * 删除数据库所有数据文件，即重置数据库
     */
    public static void resetDB() {
        // 使用常量定义的目录路径
        List<String> filePath = new ArrayList<>();
        filePath.add(SYSTEM_TABLE_DIR);
        filePath.add(LOG_BASE_DIR);
        filePath.add(DATABASE_DIR);

        // 遍历删除文件
        for (String path : filePath) {
            File directory = new File(path);

            // 检查目录是否存在
            if (!directory.exists()) {
                System.out.println("目录不存在：" + path);
                continue;
            }

            // 获取目录中的所有文件
            File[] files = directory.listFiles();
            if (files == null) { continue; }
            for (File file : files) {
                // 删除文件
                if (file.delete()) {
                    System.out.println("已删除文件：" + file.getAbsolutePath());
                } else {
                    System.out.println("无法删除文件：" + file.getAbsolutePath());
                }
            }

            // 删除当前目录
            if (directory.delete()) {
                System.out.println("已删除目录：" + directory.getAbsolutePath());
            } else {
                System.out.println("无法删除目录：" + directory.getAbsolutePath());
            }
        }

        // 清除内存中的系统表、数据表和缓存
        edu.whu.tmdb.storage.memory.MemManager.getInstance().clearAll();
    }
    public static String getResultString(SelectResult result) throws TMDBException {
        StringBuilder resultString = new StringBuilder();

        // 获取 className 和第一个 classId
        StringBuilder tableHeader = new StringBuilder("|");
        for (int i = 0; i < result.getAttrname().length; i++) {
            tableHeader.append(String.format("%-20s", result.getAttrname()[i])).append("|");
        }
        resultString.append(result.getClassName()[0]).append("\n");
        resultString.append(tableHeader.toString()).append("\n");

        boolean isunion = result.getisUnion();
        String classname = result.getClassName()[0];
        int kk = MemConnect.getClassId(classname);

        // 获取 deputy type
        String deputyType = InsertImpl.getdeputyrule(kk,1);
        boolean isUnionDeputy = "uniondeputy".equals(deputyType);

        // 创建用于去重的 Set
        Set<String> uniqueResults = new HashSet<>();

        // 输出元组信息
        for (Tuple tuple : result.getTpl().tuplelist) {
            StringBuilder data = new StringBuilder("|");

            for (int i = 0; i < tuple.tuple.length; i++) {
                Object rawValue = tuple.tuple[i];
                String value;

                // 如果是浮点数，则保留两位小数
                if (rawValue instanceof Float || rawValue instanceof Double || rawValue instanceof BigDecimal) {
                    value = String.format("%.2f", ((Number) rawValue).doubleValue());
                } else {
                    value = (rawValue != null) ? rawValue.toString() : "NULL";
                }

                // 强制截断，确保不超过20个字符
                if (value.length() > 20) {
                    value = value.substring(0, 20);
                }

                String formattedValue = String.format("%-20s", value);
                data.append(formattedValue).append("|");
            }

            // 如果是 "uniondeputy" 或 isunion 为 true，去重
            if (isUnionDeputy || isunion) {
                String rowString = data.toString();
                if (uniqueResults.add(rowString)) {
                    resultString.append(rowString).append("\n");
                }
            } else {
                resultString.append(data.toString()).append("\n");
            }
        }

        return resultString.toString();
    }


    public static void showBiPointerTable() {
        // 输出表头信息
        String[] headstring = {"class id", "object id", "deputy id", "deputyobject id"};
        StringBuilder tableHeader = new StringBuilder("|");
        for (int i = 0; i < headstring.length; i++) {
            tableHeader.append(String.format("%-20s", headstring[i])).append("|");
        }
        System.out.println(tableHeader);
        // 输出表项信息
        for (BiPointerTableItem bpti :MemConnect.getBiPointerTableList()) {
            StringBuilder data = new StringBuilder("|");
            data.append(String.format("%-20s", bpti.classid)).append("|");
            data.append(String.format("%-20s", bpti.objectid)).append("|");
            data.append(String.format("%-20s", bpti.deputyid)).append("|");
            data.append(String.format("%-20s", bpti.deputyobjectid)).append("|");
            System.out.println(data);
        }
    }

    public static void showAttributeTable() {
        // 输出表头信息
        String[] headstring = {"class id", "attribute id", "attr name", "attr type"};
        StringBuilder tableHeader = new StringBuilder("|");
        for (int i = 0; i < headstring.length; i++) {
            tableHeader.append(String.format("%-20s", headstring[i])).append("|");
        }
        System.out.println(tableHeader);
        // 输出表项信息
        for (AttributeTableItem abti :MemConnect.getAttributeTableList()) {
            StringBuilder data = new StringBuilder("|");
            data.append(String.format("%-20s", abti.classid)).append("|");
            data.append(String.format("%-20s", abti.attrid)).append("|");
            data.append(String.format("%-20s", abti.attrname)).append("|");
            data.append(String.format("%-20s", abti.attrtype)).append("|");
            System.out.println(data);
        }
    }

    public static void showClassTable() {
        // 输出表头信息
        String[] headstring = {"class name", "class id", "class type"};
        StringBuilder tableHeader = new StringBuilder("|");
        for (int i = 0; i < headstring.length; i++) {
            tableHeader.append(String.format("%-20s", headstring[i])).append("|");
        }
        System.out.println(tableHeader);
        // 输出表项信息
        for (ClassTableItem cti :MemConnect.getClassTableList()) {
            StringBuilder data = new StringBuilder("|");
            data.append(String.format("%-20s", cti.classname)).append("|");
            data.append(String.format("%-20s", cti.classid)).append("|");
            data.append(String.format("%-20s", cti.classtype)).append("|");
            System.out.println(data);
        }
    }

    public static void showDeputyTable() {
        // 输出表头信息
        String[] headstring = {"origin class id", "deputy class id","deputy rule id"};
        StringBuilder tableHeader = new StringBuilder("|");
        for (int i = 0; i < headstring.length; i++) {
            tableHeader.append(String.format("%-20s", headstring[i])).append("|");
        }
        System.out.println(tableHeader);
        // 输出表项信息
        for (DeputyTableItem dti :MemConnect.getDeputyTableList()) {
            StringBuilder data = new StringBuilder("|");
            data.append(String.format("%-20s", dti.originid)).append("|");
            data.append(String.format("%-20s", dti.deputyid)).append("|");
            data.append(String.format("%-20s", dti.ruleid)).append("|");
            System.out.println(data);
        }
    }

    public static void showSwitchingTable() {
        // 输出表头信息
        String[] headstring = {"origin class id", "origin attr id", "origin attr name",
                "deputy class id","deputy attr id", "deputy attr name"};
        StringBuilder tableHeader = new StringBuilder("|");
        for (int i = 0; i < headstring.length; i++) {
            tableHeader.append(String.format("%-20s", headstring[i])).append("|");
        }
        System.out.println(tableHeader);
        // 输出表项信息
        for (SwitchingTableItem sti :MemConnect.getSwitchingTableList()) {
            StringBuilder data = new StringBuilder("|");
            data.append(String.format("%-20s", sti.oriId)).append("|");
            data.append(String.format("%-20s", sti.oriAttrid)).append("|");
            data.append(String.format("%-20s", sti.oriAttr)).append("|");
            data.append(String.format("%-20s", sti.deputyId)).append("|");
            data.append(String.format("%-20s", sti.deputyAttrId)).append("|");
            data.append(String.format("%-20s", sti.deputyAttr)).append("|");
            System.out.println(data);
        }
    }
    public static String getBiPointerTableString() {
        // 输出表头信息
        String[] headstring = {"class id", "object id", "deputy id", "deputyobject id"};
        StringBuilder result = new StringBuilder("|");
        for (int i = 0; i < headstring.length; i++) {
            result.append(String.format("%-20s", headstring[i])).append("|");
        }
        result.append("\n");

        // 输出表项信息
        for (BiPointerTableItem bpti : MemConnect.getBiPointerTableList()) {
            result.append("|");
            result.append(String.format("%-20s", bpti.classid)).append("|");
            result.append(String.format("%-20s", bpti.objectid)).append("|");
            result.append(String.format("%-20s", bpti.deputyid)).append("|");
            result.append(String.format("%-20s", bpti.deputyobjectid)).append("|");
            result.append("\n");
        }

        return result.toString();
    }
    public static String getClassTableString() {
        // 输出表头信息
        String[] headstring = {"class name", "class id", "class type"};
        StringBuilder result = new StringBuilder("|");
        for (int i = 0; i < headstring.length; i++) {
            result.append(String.format("%-20s", headstring[i])).append("|");
        }
        result.append("\n");

        // 输出表项信息
        for (ClassTableItem cti : MemConnect.getClassTableList()) {
            result.append("|");
            result.append(String.format("%-20s", cti.classname)).append("|");
            result.append(String.format("%-20s", cti.classid)).append("|");
            result.append(String.format("%-20s", cti.classtype)).append("|");
            result.append("\n");
        }

        return result.toString();
    }
    public static String getArributeTableString() {
        // 输出表头信息
        String[] headstring = {"class id", "attribute id", "attr name", "attr type"};
        StringBuilder result = new StringBuilder("|");
        for (int i = 0; i < headstring.length; i++) {
            result.append(String.format("%-20s", headstring[i])).append("|");
        }
        result.append("\n");

        // 输出表项信息
        for (AttributeTableItem abti :MemConnect.getAttributeTableList()) {
            result.append("|");
            result.append(String.format("%-20s", abti.classid)).append("|");
            result.append(String.format("%-20s", abti.attrid)).append("|");
            result.append(String.format("%-20s", abti.attrname)).append("|");
            result.append(String.format("%-20s", abti.attrtype)).append("|");
            result.append("\n");
        }

        return result.toString();
    }
    public static String getDeputyTableString() {
        // 输出表头信息
        String[] headstring = {"origin class id", "deputy class id","deputy rule id"};
        StringBuilder result = new StringBuilder("|");
        for (int i = 0; i < headstring.length; i++) {
            result.append(String.format("%-20s", headstring[i])).append("|");
        }
        result.append("\n");

        // 输出表项信息
        for (DeputyTableItem dti : MemConnect.getDeputyTableList()) {
            result.append("|");
            result.append(String.format("%-20s", dti.originid)).append("|");
            result.append(String.format("%-20s", dti.deputyid)).append("|");
            result.append(String.format("%-20s", dti.ruleid)).append("|");
            result.append("\n");
        }

        return result.toString();
    }
    public static String getSwitchingTableString() {
        // 输出表头信息
        String[] headstring = {"origin class id", "origin attr id", "origin attr name",
                "deputy class id", "deputy attr id", "deputy attr name"};
        StringBuilder result = new StringBuilder("|");
        for (int i = 0; i < headstring.length; i++) {
            result.append(String.format("%-20s", headstring[i])).append("|");
        }
        result.append("\n");

        // 输出表项信息
        for (SwitchingTableItem sti : MemConnect.getSwitchingTableList()) {
            result.append("|");
            result.append(String.format("%-20s", sti.oriId)).append("|");
            result.append(String.format("%-20s", sti.oriAttrid)).append("|");
            result.append(String.format("%-20s", sti.oriAttr)).append("|");
            result.append(String.format("%-20s", sti.deputyId)).append("|");
            result.append(String.format("%-20s", sti.deputyAttrId)).append("|");
            result.append(String.format("%-20s", sti.deputyAttr)).append("|");
            result.append("\n");
        }

        return result.toString();
    }
    public static String getResetDB() {
        // 使用常量定义的目录路径
        List<String> filePath = new ArrayList<>();
        filePath.add(SYSTEM_TABLE_DIR);
        filePath.add(LOG_BASE_DIR);
        filePath.add(DATABASE_DIR);

        // 遍历删除文件
        for (String path : filePath) {
            File directory = new File(path);

            // 检查目录是否存在
            if (!directory.exists()) {
                System.out.println("目录不存在：" + path);
                continue;
            }

            // 获取目录中的所有文件
            File[] files = directory.listFiles();
            if (files == null) { continue; }
            for (File file : files) {
                // 删除文件
                if (file.delete()) {
                    System.out.println("已删除文件：" + file.getAbsolutePath());
                } else {
                    System.out.println("无法删除文件：" + file.getAbsolutePath());
                }
            }

            // 删除当前目录
            if (directory.delete()) {
                System.out.println("已删除目录：" + directory.getAbsolutePath());
            } else {
                System.out.println("无法删除目录：" + directory.getAbsolutePath());
            }
        }
        // 清除内存中的系统表、数据表和缓存
        edu.whu.tmdb.storage.memory.MemManager.getInstance().clearAll();
        return "reset db success";
    }


}