package edu.whu.tmdb.storage.memory.SystemTable;

import java.io.Serializable;
import java.util.Objects;

public class ClassTableItem implements Serializable {
    public String classname = "";       // 类名
    public int classid = 0;             // 类id
    public int attrnum = 0;             // 类属性总个数
    public int classtype = 0;       // 0-select, 1-join, 2-union, 3-group

    public ClassTableItem(String classname, int classid, int attrnum, int classtype) {
        this.classname = classname;
        this.classid = classid;
        this.attrnum = attrnum;
        this.classtype = classtype;
    }
    public ClassTableItem(){}

    public ClassTableItem getCopy(){
        return new ClassTableItem(this.classname, this.classid, this.attrnum, this.classtype);
    }

    @Override
    public boolean equals(Object object){
        if (this == object) { return true; }
        if (!(object instanceof ClassTableItem)) {
            return false;
        }

        ClassTableItem oi = (ClassTableItem) object;
        if(this.classid!=oi.classid){
            return false;
        }
        if(this.classname!=oi.classname){
            return false;
        }
        if(this.classtype!=oi.classtype){
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + Objects.hash(this.classid)
                +Objects.hash(this.classname)
                +Objects.hash(this.classtype)
                +Objects.hash(this.attrnum)
        ;
        return result;
    }


}
