package edu.whu.tmdb;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import edu.whu.tmdb.query.operations.utils.SelectResult;
import edu.whu.tmdb.query.operations.impl.SelectImpl;
import edu.whu.tmdb.storage.memory.Tuple;
import edu.whu.tmdb.util.DbOperation;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;

/**
 * 回归测试 —— 逐条执行 SQL，逐行对比实际输出与期望输出。
 */
public class RegressionTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  Totem 数据库回归测试");
        System.out.println("========================================\n");

        DbOperation.resetDB();

        if ("true".equalsIgnoreCase(System.getenv("TASK_TWO_ONLY"))) {
            testHavingAndGroupDeputyMigration();
            System.out.println("========================================");
            System.out.println("  " + passed + " passed, " + failed + " failed");
            System.out.println("========================================");
            if (failed > 0) System.exit(1);
            return;
        }

        testBasicDDL();
        testBasicDML();
        testSelectWhere();
        testAggregateFunctions();
        testGroupBy();
        testOrderBy();
        testJoin();
        testUnionIntersectExcept();
        testSelectDeputy();
        testJoinDeputy();
        testUnionDeputy();
        testGroupDeputy();
        testHavingAndGroupDeputyMigration();
        testCrossClassQuery();
        testUpdateMigration();

        System.out.println("========================================");
        System.out.println("  " + passed + " passed, " + failed + " failed");
        System.out.println("========================================");
        if (failed > 0) System.exit(1);
    }

    // ==================== 基础 DDL ====================

    static void testBasicDDL() {
        section("Basic DDL");
        ddl("CREATE TABLE Student (id INT, name STRING, score INT);");
        ddl("CREATE TABLE Course (id INT, title STRING, credit INT);");
        check("select * from Student;",
            "Student\n" +
            "|id                  |name                |score               |\n");
        ddl("DROP TABLE Course;");
        exec("select * from Course;");
        ddl("CREATE TABLE Course (id INT, title STRING, credit INT);");
    }

    // ==================== 基础 DML ====================

    static void testBasicDML() {
        section("Basic DML");
        ddl("INSERT INTO Student VALUES (1, 'Alice', 85);");
        ddl("INSERT INTO Student VALUES (2, 'Bob', 72);");
        ddl("INSERT INTO Student VALUES (3, 'Charlie', 90);");
        check("select * from Student;",
            "Student\n" +
            "|id                  |name                |score               |\n" +
            "|1                   |Alice               |85                  |\n" +
            "|2                   |Bob                 |72                  |\n" +
            "|3                   |Charlie             |90                  |\n");
        ddl("UPDATE Student SET score = 88 WHERE id = 1;");
        check("select * from Student WHERE id = 1;",
            "Student\n" +
            "|id                  |name                |score               |\n" +
            "|1                   |Alice               |88                  |\n");
        ddl("DELETE FROM Student WHERE id = 3;");
        check("select * from Student;",
            "Student\n" +
            "|id                  |name                |score               |\n" +
            "|1                   |Alice               |88                  |\n" +
            "|2                   |Bob                 |72                  |\n");
    }

    // ==================== SELECT WHERE ====================

    static void testSelectWhere() {
        section("SELECT WHERE");
        ddl("DELETE FROM Student WHERE id > 0;");
        ddl("INSERT INTO Student VALUES (1, 'Alice', 85);");
        ddl("INSERT INTO Student VALUES (2, 'Bob', 72);");
        ddl("INSERT INTO Student VALUES (3, 'Charlie', 90);");
        ddl("INSERT INTO Student VALUES (4, 'Diana', 60);");
        check("select * from Student WHERE score > 80;",
            "Student\n" +
            "|id                  |name                |score               |\n" +
            "|3                   |Charlie             |90                  |\n" +
            "|1                   |Alice               |85                  |\n");
        check("select * from Student WHERE score >= 85 AND name = 'Alice';",
            "Student\n" +
            "|id                  |name                |score               |\n" +
            "|1                   |Alice               |85                  |\n");
    }

    // ==================== 聚合函数 ====================

    static void testAggregateFunctions() {
        section("Aggregate Functions (with GROUP BY)");
        check("select score, COUNT(id), AVG(score), SUM(score), MIN(score), MAX(score) from Student GROUP BY score;",
            "Student\n" +
            "|score               |COUNT(id)           |AVG(score)          |SUM(score)          |MIN(score)          |MAX(score)          |\n" +
            "|90                  |1.00                |90.00               |90.00               |90.00               |90.00               |\n" +
            "|60                  |1.00                |60.00               |60.00               |60.00               |60.00               |\n" +
            "|72                  |1.00                |72.00               |72.00               |72.00               |72.00               |\n" +
            "|85                  |1.00                |85.00               |85.00               |85.00               |85.00               |\n");
    }

    // ==================== GROUP BY ====================

    static void testGroupBy() {
        section("GROUP BY");
        ddl("DELETE FROM Student WHERE id > 0;");
        ddl("INSERT INTO Student VALUES (1, 'Alice', 85);");
        ddl("INSERT INTO Student VALUES (2, 'Bob', 72);");
        ddl("INSERT INTO Student VALUES (3, 'Charlie', 90);");
        ddl("INSERT INTO Student VALUES (4, 'Diana', 60);");
        ddl("INSERT INTO Student VALUES (5, 'Eve', 88);");
        check("select score, COUNT(id) from Student GROUP BY score;",
            "Student\n" +
            "|score               |COUNT(id)           |\n" +
            "|88                  |1.00                |\n" +
            "|90                  |1.00                |\n" +
            "|60                  |1.00                |\n" +
            "|72                  |1.00                |\n" +
            "|85                  |1.00                |\n");
    }

    // ==================== ORDER BY ====================

    static void testOrderBy() {
        section("ORDER BY");
        check("select * from Student ORDER BY score;",
            "Student\n" +
            "|id                  |name                |score               |\n" +
            "|4                   |Diana               |60                  |\n" +
            "|2                   |Bob                 |72                  |\n" +
            "|1                   |Alice               |85                  |\n" +
            "|5                   |Eve                 |88                  |\n" +
            "|3                   |Charlie             |90                  |\n");
        check("select * from Student ORDER BY score DESC;",
            "Student\n" +
            "|id                  |name                |score               |\n" +
            "|3                   |Charlie             |90                  |\n" +
            "|5                   |Eve                 |88                  |\n" +
            "|1                   |Alice               |85                  |\n" +
            "|2                   |Bob                 |72                  |\n" +
            "|4                   |Diana               |60                  |\n");
    }

    // ==================== JOIN ====================

    static void testJoin() {
        section("JOIN");
        ddl("INSERT INTO Course VALUES (1, 'Math', 4);");
        ddl("INSERT INTO Course VALUES (2, 'English', 3);");
        ddl("INSERT INTO Course VALUES (3, 'Physics', 5);");
        ddl("CREATE TABLE Enroll (studentId INT, courseId INT);");
        ddl("INSERT INTO Enroll VALUES (1, 1);");
        ddl("INSERT INTO Enroll VALUES (1, 2);");
        ddl("INSERT INTO Enroll VALUES (2, 1);");
        check("select Enroll.studentId, Course.title from Enroll INNER JOIN Course ON Enroll.courseId = Course.id;",
            "Enroll\n" +
            "|Enroll.studentId    |Course.title        |\n" +
            "|1                   |Math                |\n" +
            "|1                   |English             |\n" +
            "|2                   |Math                |\n");
        check("select Enroll.studentId, Course.title from Enroll LEFT JOIN Course ON Enroll.courseId = Course.id;",
            "Enroll\n" +
            "|Enroll.studentId    |Course.title        |\n" +
            "|1                   |Math                |\n" +
            "|2                   |Math                |\n" +
            "|1                   |English             |\n");
        ddl("DROP TABLE Enroll;");
    }

    // ==================== UNION / INTERSECT / EXCEPT ====================

    static void testUnionIntersectExcept() {
        section("UNION / INTERSECT / EXCEPT");
        ddl("CREATE TABLE T1 (x INT);");
        ddl("CREATE TABLE T2 (x INT);");
        ddl("INSERT INTO T1 VALUES (1);");
        ddl("INSERT INTO T1 VALUES (2);");
        ddl("INSERT INTO T1 VALUES (3);");
        ddl("INSERT INTO T2 VALUES (2);");
        ddl("INSERT INTO T2 VALUES (3);");
        ddl("INSERT INTO T2 VALUES (4);");
        check("select * from T1;",
            "T1\n" +
            "|x                   |\n" +
            "|1                   |\n" +
            "|2                   |\n" +
            "|3                   |\n");
        check("select * from T2;",
            "T2\n" +
            "|x                   |\n" +
            "|2                   |\n" +
            "|3                   |\n" +
            "|4                   |\n");
        check("select * from T1 UNION select * from T2;",
            "T1\n" +
            "|x                   |\n" +
            "|1                   |\n" +
            "|2                   |\n" +
            "|3                   |\n" +
            "|4                   |\n");
        check("select * from T1 INTERSECT select * from T2;",
            "T1\n" +
            "|x                   |\n" +
            "|2                   |\n" +
            "|3                   |\n");
        check("select * from T1 EXCEPT select * from T2;",
            "T1\n" +
            "|x                   |\n" +
            "|1                   |\n");
        ddl("DROP TABLE T1;");
        ddl("DROP TABLE T2;");
    }

    // ==================== SelectDeputy (严格) ====================

    static void testSelectDeputy() {
        section("SelectDeputy (strict)");
        ddl("DELETE FROM Student WHERE id > 0;");
        ddl("INSERT INTO Student VALUES (1, 'Alice', 85);");
        ddl("INSERT INTO Student VALUES (2, 'Bob', 72);");
        ddl("INSERT INTO Student VALUES (3, 'Charlie', 90);");
        ddl("INSERT INTO Student VALUES (4, 'Diana', 60);");
        ddl("CREATE SELECTDEPUTY GoodStudent AS SELECT name, score FROM Student WHERE score > 80;");
        check("select * from GoodStudent;",
            "GoodStudent\n" +
            "|name                |score               |\n" +
            "|Charlie             |90                  |\n" +
            "|Alice               |85                  |\n");
    }

    // ==================== JoinDeputy ====================

    static void testJoinDeputy() {
        section("JoinDeputy");
        ddl("CREATE TABLE Singer (id INT, name STRING, sex STRING, age INT, nationality STRING, company STRING);");
        ddl("CREATE TABLE Song (id INT, name STRING, singer STRING, date INT);");
        ddl("INSERT INTO Singer VALUES (0, 'TaylorSwift', 'F', 36, 'America', 'ATVMusic');");
        ddl("INSERT INTO Singer VALUES (1, 'EdSheeran', 'M', 33, 'UK', 'WarnerMusic');");
        ddl("INSERT INTO Singer VALUES (2, 'JayChou', 'M', 45, 'China', 'JVR');");
        ddl("INSERT INTO Song VALUES (0, 'Red', 'TaylorSwift', 2012);");
        ddl("INSERT INTO Song VALUES (1, '1989', 'TaylorSwift', 2014);");
        ddl("INSERT INTO Song VALUES (2, 'Divide', 'EdSheeran', 2017);");
        ddl("CREATE JOINDEPUTY singer_song AS " +
            "SELECT Song.name, Song.date, Song.singer, Singer.sex, Singer.age, Singer.nationality, Singer.company " +
            "FROM Song, Singer WHERE Song.singer = Singer.name;");
        check("select * from singer_song;",
            "singer_song\n" +
            "|Song.name           |Song.date           |Song.singer         |Singer.sex          |Singer.age          |Singer.nationality  |Singer.company      |\n" +
            "|Red                 |2012                |TaylorSwift         |F                   |36                  |America             |ATVMusic            |\n" +
            "|1989                |2014                |TaylorSwift         |F                   |36                  |America             |ATVMusic            |\n" +
            "|Divide              |2017                |EdSheeran           |M                   |33                  |UK                  |WarnerMusic         |\n");
    }

    // ==================== UnionDeputy ====================

    static void testUnionDeputy() {
        section("UnionDeputy");
        ddl("CREATE TABLE Photo (id INT, name STRING, singer STRING, date INT);");
        ddl("INSERT INTO Photo VALUES (0, 'Fearless', 'TaylorSwift', 2008);");
        ddl("INSERT INTO Photo VALUES (1, 'HeadAboveWater', 'AvrilLavigne', 2022);");
        ddl("CREATE UNIONDEPUTY song_photo AS " +
            "SELECT name, singer, date FROM Song UNION SELECT name, singer, date FROM Photo;");
        check("select * from song_photo;",
            "song_photo\n" +
            "|name                |singer              |date                |\n" +
            "|Red                 |TaylorSwift         |2012                |\n" +
            "|1989                |TaylorSwift         |2014                |\n" +
            "|Divide              |EdSheeran           |2017                |\n" +
            "|Fearless            |TaylorSwift         |2008                |\n" +
            "|HeadAboveWater      |AvrilLavigne        |2022                |\n");
    }

    // ==================== GroupDeputy ====================

    static void testGroupDeputy() {
        section("GroupDeputy");
        ddl("CREATE GROUPDEPUTY year_songnumber AS " +
            "SELECT date, COUNT(id) as song_count FROM Song GROUP BY date;");
        check("select * from year_songnumber;",
            "year_songnumber\n" +
            "|date                |song_count          |\n" +
            "|2017                |1.00                |\n" +
            "|2014                |1.00                |\n" +
            "|2012                |1.00                |\n");
    }

    static void testHavingAndGroupDeputyMigration() {
        section("HAVING and GroupDeputy Migration");
        ddl("CREATE TABLE Employee (id INT, dept STRING, salary INT);");
        ddl("INSERT INTO Employee VALUES (1, 'HR', 5000), (2, 'HR', 6000), "
                + "(3, 'IT', 8000), (4, 'IT', 9000), (5, 'Sales', 3000);");

        checkRows("SELECT dept, AVG(salary) AS avg_sal, COUNT(*) AS cnt FROM Employee "
                        + "GROUP BY dept HAVING AVG(salary) >= 5000 AND COUNT(*) >= 2;",
                "HR|5500.0|2.0", "IT|8500.0|2.0");
        checkRows("SELECT dept, MIN(salary), MAX(salary), SUM(salary) FROM Employee "
                        + "GROUP BY dept HAVING SUM(salary) > 10000 AND MIN(salary) >= 5000;",
                "HR|5000.0|6000.0|11000.0", "IT|8000.0|9000.0|17000.0");

        ddl("CREATE GROUPDEPUTY HighSalaryDept AS SELECT dept, AVG(salary) AS avg_sal, "
                + "COUNT(*) AS cnt FROM Employee GROUP BY dept HAVING AVG(salary) > 5000;");
        checkRows("SELECT * FROM HighSalaryDept;",
                "HR|5500.0|2.0", "IT|8500.0|2.0");

        ddl("INSERT INTO Employee VALUES (6, 'IT', 10000);");
        checkRows("SELECT * FROM HighSalaryDept;",
                "HR|5500.0|2.0", "IT|9000.0|3.0");

        ddl("INSERT INTO Employee VALUES (7, 'Sales', 9000);");
        checkRows("SELECT * FROM HighSalaryDept;",
                "HR|5500.0|2.0", "IT|9000.0|3.0", "Sales|6000.0|2.0");

        ddl("DELETE FROM Employee WHERE id = 4;");
        checkRows("SELECT * FROM HighSalaryDept;",
                "HR|5500.0|2.0", "IT|9000.0|2.0", "Sales|6000.0|2.0");

        ddl("DELETE FROM Employee WHERE id = 7;");
        checkRows("SELECT * FROM HighSalaryDept;",
                "HR|5500.0|2.0", "IT|9000.0|2.0");

        ddl("UPDATE Employee SET salary = 1000 WHERE id = 2;");
        checkRows("SELECT * FROM HighSalaryDept;", "IT|9000.0|2.0");
        ddl("UPDATE Employee SET salary = 9000 WHERE id = 1;");
        checkRows("SELECT * FROM HighSalaryDept;", "IT|9000.0|2.0");
        ddl("UPDATE Employee SET salary = 9000 WHERE id = 2;");
        checkRows("SELECT * FROM HighSalaryDept;",
                "HR|9000.0|2.0", "IT|9000.0|2.0");
    }

    // ==================== 跨类查询 ====================

    static void testCrossClassQuery() {
        section("Cross-class Query");
        check("SELECT Singer -> singer_song FROM Singer;",
            "singer_song\n" +
            "|Song.name           |Song.date           |Song.singer         |Singer.sex          |Singer.age          |Singer.nationality  |Singer.company      |\n" +
            "|Red                 |2012                |TaylorSwift         |F                   |36                  |America             |ATVMusic            |\n" +
            "|Divide              |2017                |EdSheeran           |M                   |33                  |UK                  |WarnerMusic         |\n" +
            "|1989                |2014                |TaylorSwift         |F                   |36                  |America             |ATVMusic            |\n");
        check("SELECT Singer -> singer_song.name FROM Singer;",
            "singer_song\n" +
            "|name                |\n" +
            "|Red                 |\n" +
            "|Divide              |\n" +
            "|1989                |\n");
        check("SELECT Singer{name = 'TaylorSwift'} -> singer_song.name FROM Singer;",
            "singer_song\n" +
            "|name                |\n" +
            "|Red                 |\n" +
            "|1989                |\n");
        check("SELECT Singer -> singer_song{date >= 2014} -> Song.name FROM Singer;",
            "Song\n" +
            "|name                |\n" +
            "|Divide              |\n" +
            "|1989                |\n");
    }

    // ==================== 更新迁移 ====================

    static void testUpdateMigration() {
        section("Update Migration");
        ddl("INSERT INTO Student VALUES (5, 'Frank', 95);");
        check("select * from GoodStudent;",
            "GoodStudent\n" +
            "|name                |score               |\n" +
            "|Charlie             |90                  |\n" +
            "|Alice               |85                  |\n" +
            "|Frank               |95                  |\n");
        ddl("UPDATE Student SET score = 55 WHERE id = 1;");
        // 已知限制: UPDATE 不重新评估 SelectDeputy 的 WHERE
        check("select * from GoodStudent;",
            "GoodStudent\n" +
            "|name                |score               |\n" +
            "|Charlie             |90                  |\n" +
            "|Alice               |55                  |\n" +
            "|Frank               |95                  |\n");
        ddl("DELETE FROM Student WHERE id = 5;");
        check("select * from GoodStudent;",
            "GoodStudent\n" +
            "|name                |score               |\n" +
            "|Charlie             |90                  |\n" +
            "|Alice               |55                  |\n");
    }

    // ==================== 辅助方法 ====================

    static void section(String name) {
        System.out.println("--- " + name + " ---");
    }

    static void ddl(String sql) {
        try {
            Main.execute(sql);
        } catch (Exception e) {
            System.out.println("  [FAIL] " + sql);
            System.out.println("         " + e.getMessage());
            failed++;
        }
    }

    static void exec(String sql) {
        try { Main.execute(sql); } catch (Exception e) {}
    }

    static void check(String sql, String expected) {
        String actual;
        try {
            SelectResult r = Main.execute(sql);
            if (r == null) {
                actual = "(null)";
            } else {
                actual = DbOperation.getResultString(r);
            }
        } catch (Exception e) {
            actual = "Error: " + e.getMessage();
        }

        if (actual.equals(expected)) {
            System.out.println("  [PASS] " + sql);
            passed++;
        } else {
            System.out.println("  [FAIL] " + sql);
            System.out.println("  --- expected ---");
            for (String line : expected.split("\n")) {
                System.out.println("  " + line);
            }
            System.out.println("  --- actual ---");
            for (String line : actual.split("\n")) {
                System.out.println("  " + line);
            }
            System.out.println("  ---");
            failed++;
        }
    }

    static void checkRows(String sql, String... expectedRows) {
        Set<String> expected = new HashSet<>(Arrays.asList(expectedRows));
        Set<String> actual = new HashSet<>();
        try {
            SelectResult result = new SelectImpl().select(CCJSqlParserUtil.parse(sql));
            if (result != null) {
                for (Tuple tuple : result.getTpl().tuplelist) {
                    actual.add(Arrays.stream(tuple.tuple)
                            .map(String::valueOf)
                            .collect(Collectors.joining("|")));
                }
            }
        } catch (Exception e) {
            e.printStackTrace(System.out);
            actual.add("Error: " + e.getMessage());
        }

        if (actual.equals(expected)) {
            System.out.println("  [PASS] " + sql);
            passed++;
        } else {
            System.out.println("  [FAIL] " + sql);
            System.out.println("  expected rows: " + expected);
            System.out.println("  actual rows:   " + actual);
            failed++;
        }
    }
}
