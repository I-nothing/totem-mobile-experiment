package edu.whu.tmdb.query.operations.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import edu.whu.tmdb.query.operations.Drop;
import edu.whu.tmdb.query.operations.Exception.TMDBException;
import edu.whu.tmdb.query.operations.utils.MemConnect;
import edu.whu.tmdb.storage.memory.MemManager;
import edu.whu.tmdb.storage.memory.SystemTable.BiPointerTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.ClassTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.DeputyTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.ObjectTableItem;
import edu.whu.tmdb.storage.memory.SystemTable.SwitchingTableItem;
import net.sf.jsqlparser.statement.Statement;

public class DropImpl implements Drop {

    private MemConnect memConnect;

    public DropImpl() {
        this.memConnect = MemConnect.getInstance(MemManager.getInstance());
    }

    @Override
    public boolean drop(Statement statement) throws TMDBException {
        return execute((net.sf.jsqlparser.statement.drop.Drop) statement);
    }

    public boolean execute(net.sf.jsqlparser.statement.drop.Drop drop) throws TMDBException {
        String tableName = drop.getName().getName();
        int classId = memConnect.getClassId(tableName);
        drop(classId);
        return true;
    }

    public void drop(int classId) {
        ArrayList<Integer> deputyClassIdList = new ArrayList<>();   // 存储该类对应所有代理类id

        try {
            dropClassTable(classId);
        } catch (TMDBException e) {
            // 处理异常的逻辑，例如打印错误信息或者其他处理
            e.printStackTrace();
        }                            // 1.删除ClassTableItem
        dropDeputyClassTable(classId, deputyClassIdList);   // 2.获取代理类id并在表中删除
        dropBiPointerTable(classId);                        // 3.删除 源类/对象<->代理类/对象 的双向关系表
        dropSwitchingTable(classId);                        // 4.删除switchingTable
        dropObjectTable(classId);                           // 5.删除已创建的源类对象
        for (Integer deputyClassId : deputyClassIdList) {
            drop(deputyClassId);
        }
        // 6.递归删除代理类相关
        // TODO-task10 删的不干净
    }

    /**
     * 给定要删除的class id，删除系统表类表(class table)中的表项
     * @param classId 要删除的表对应的id
     * @throws TMDBException 不存在给定表名的表，抛出异常
     */
    private void dropClassTable(int classId) throws TMDBException{
        //int a=0;
        Iterator<ClassTableItem> iterator = MemConnect.getClassTableList().iterator();
        while (iterator.hasNext()) {
            ClassTableItem classTableItem = iterator.next();
            if (classTableItem.classid == classId) {
                iterator.remove();
                //a++;
            }
        }
        //if(a==0){throw new TMDBException(ErrorList.CLASS_ID_DOES_NOT_EXIST, String.valueOf(classId));}
    }

    /**
     * 删除系统表中的deputy table，并获取class id对应源类的代理类id
     * @param classId 源类id
     * @param deputyClassIdList 作为返回值，源类对应的代理类id列表
     */
    private void dropDeputyClassTable(int classId, ArrayList<Integer> deputyClassIdList) {
        Set<Integer> newDeputyClassIdSet = new HashSet<>();
        Iterator<DeputyTableItem> iterator = MemConnect.getDeputyTableList().iterator();
        while (iterator.hasNext()) {
            
            DeputyTableItem deputyTableItem = iterator.next();
            if (deputyTableItem.originid == classId) {
                newDeputyClassIdSet.add(deputyTableItem.deputyid);
                iterator.remove();
            }
        }
        deputyClassIdList.addAll(newDeputyClassIdSet);
    }

    /**
     * 删除系统表中的BiPointerTable
     * @param classId 源类id
     */
    private void dropBiPointerTable(int classId) {
        Iterator<BiPointerTableItem> iterator = MemConnect.getBiPointerTableList().iterator();
        while (iterator.hasNext()) {
            BiPointerTableItem biPointerTableItem = iterator.next();
            if (biPointerTableItem.classid == classId /*|| biPointerTableItem.deputyid == classId*/) {
                iterator.remove();
            }
        }
    }

    /**
     * 删除系统表中的SwitchingTable
     * @param classId 源类id
     */
    private void dropSwitchingTable(int classId) {
        Iterator<SwitchingTableItem> iterator = MemConnect.getSwitchingTableList().iterator();
        while (iterator.hasNext()) {
            SwitchingTableItem switchingTableItem = iterator.next();
            if (switchingTableItem.oriId == classId) {
                iterator.remove();
            }
        }
    }

    /**
     * 删除源类具有的所有对象的列表
     * @param classId 源类id
     */
    private void dropObjectTable(int classId) {
        Iterator<ObjectTableItem> iterator = MemConnect.getObjectTableList().iterator();
        while (iterator.hasNext()) {
            ObjectTableItem objectTableItem = iterator.next();
            if (objectTableItem.classid == classId) {
                iterator.remove();
            }
        }
    }
}
