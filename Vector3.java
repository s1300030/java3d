import java.util.*;
import java.awt.*;

// ====== 3D vector ======
public class Vector3 {
    double x, y, z;
    Vector3(double x, double y, double z) { this.x=x; this.y=y; this.z=z; }
    Vector3 add(Vector3 o){ return new Vector3(x+o.x, y+o.y, z+o.z); }
    Vector3 sub(Vector3 o){ return new Vector3(x-o.x, y-o.y, z-o.z); }
    Vector3 mul(double s){ return new Vector3(x*s, y*s, z*s); }
    double norm(){ return Math.sqrt(x*x + y*y + z*z); }
    Vector3 normalize(){
        double l = norm();
        if(l < 1e-12) return new Vector3(0,0,0);
        return new Vector3(x/l, y/l, z/l);
    }
}


