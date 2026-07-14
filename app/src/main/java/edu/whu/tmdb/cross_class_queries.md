=== 跨类查询测试 SQL 语句（按顺序逐行执行） ===

--- 0. 清理环境 ---
resetdb

--- 1. 创建基础类 ---
create class Singer(id int, name String, sex String, age String, nationality String, company String);
create class Song(id int, name String, singer String, date int, description String);

--- 2. 插入测试数据 ---
insert into Singer values(0, 'TaylorSwift', 'F', 36, 'America', 'ATVMusic');
insert into Singer values(1, 'EdSheeran', 'M', 33, 'UK', 'WarnerMusic');
insert into Singer values(2, 'Adele', 'F', 35, 'UK', 'Columbia');
insert into Song values(0, 'Red', 'TaylorSwift', 2012, 'Country pop album from 2012');
insert into Song values(1, '1989', 'TaylorSwift', 2014, 'Synth-pop album from 2014');
insert into Song values(2, 'Divide', 'EdSheeran', 2017, 'Pop album from 2017');
insert into Song values(3, 'Hello', 'Adele', 2015, 'Soul ballad from 2015');

--- 3. 验证基础数据 ---
select * from Singer;
select * from Song;

--- 4. 创建 joindeputy（不含 Song.description，用于验证逆向导航）---
create joindeputy singer_song as select Song.name, Song.date, Song.singer, Singer.sex, Singer.age, Singer.nationality from Song, Singer where Song.singer = Singer.name;

--- 5. 验证 deputy 类 ---
select * from singer_song;

--- 6. 跨类查询测试 ---

-- 1. 单步导航 + 全属性
SELECT Singer -> singer_song FROM Singer;

-- 2. 单步导航 + 单属性投影
SELECT Singer -> singer_song.name FROM Singer;

-- 3. 起始类筛选 {条件}
SELECT Singer{name = 'TaylorSwift'} -> singer_song.name FROM Singer;

-- 4. 逆向导航（description 不在 singer_song 中，必须从代理类跳回源类 Song）
SELECT Singer -> singer_song -> Song.description FROM Singer;

-- 5. 中间类筛选 {条件}
SELECT Singer -> singer_song{date >= 2014} -> Song.description FROM Singer;

-- 6. AND 复合筛选（sex='F' 筛掉 EdSheeran，age>35 筛掉 Adele，仅保留 TaylorSwift）
SELECT Singer{sex = 'F' AND age > 35} -> singer_song -> Song.description FROM Singer;
