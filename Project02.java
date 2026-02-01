import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import javax.swing.*;

public class Project02{
    static final int[][] VERT_OFF = {
        {0,0,0},{1,0,0},{1,1,0},{0,1,0},
        {0,0,1},{1,0,1},{1,1,1},{0,1,1}
    };

    static final int[][] EDGE_VERT = {
        {0,1},{1,2},{2,3},{3,0},
        {4,5},{5,6},{6,7},{7,4},
        {0,4},{1,5},{2,6},{3,7}
    };

    public static void main(String[] args){

         // == .xyz loading==
         ArrayList<PointN> pts = null;
        if(args.length < 1||args.length>=3)
        {
            System.out.println("Please input fileName (% java Project02 xyz/bunny.xyz)");
            System.exit(1);
        }

        else{
            try {
                pts = loadXYZ(args[0]);

            } catch (IOException ex){
                ex.printStackTrace();
                return;
            }
        }

        if(pts.isEmpty()){
            System.out.println("No points loaded. Check file format.");
            return;
        }

        System.out.println("Drag: rotate | Wheel: zoom | Points: " + pts.size());
        ArrayList<Constraint> C = buildConstraints(pts, pts.size()/5);
        double[] lambda = fitRBF(C);

        double maxAbs = 0.0;
        for(int i=0;i<Math.min(50, C.size()); i++){
            double v = evalRBF(C, lambda, C.get(i).x);
            double err = Math.abs(v - C.get(i).y);
            maxAbs = Math.max(maxAbs, err);
        }
        BBox b = expand(bbox(pts), 0.10);
        ScalarField field = buildField(C, lambda, b, 64);

        Mesh mesh = marchingCubes(field, 0.0);

        // == Setting Window ==
        JFrame f = new JFrame("Project02");

        int w = 700;
        int h = 700;
        
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setSize(w, h);
        f.setLocationRelativeTo(null);

        ViewerPanel panel = new ViewerPanel(pts);
        panel.setMesh(mesh);
        f.add(panel);

        f.setVisible(true);
        

    }

    static double bboxDiag(ArrayList<PointN> pts){
        double minx=1e100, miny=1e100, minz=1e100;
        double maxx=-1e100, maxy=-1e100, maxz=-1e100;
        for(PointN pn: pts){
            Vector3 p = pn.p;
            minx=Math.min(minx,p.x); miny=Math.min(miny,p.y); minz=Math.min(minz,p.z);
            maxx=Math.max(maxx,p.x); maxy=Math.max(maxy,p.y); maxz=Math.max(maxz,p.z);
        }
        double dx=maxx-minx, dy=maxy-miny, dz=maxz-minz;
        return Math.sqrt(dx*dx+dy*dy+dz*dz);
    }

    static ArrayList<PointN> samplePoints(ArrayList<PointN> pts, int maxN, long seed){
        if(pts.size() <= maxN) return new ArrayList<>(pts);
        ArrayList<PointN> copy = new ArrayList<>(pts);
        Collections.shuffle(copy, new Random(seed));
        return new ArrayList<>(copy.subList(0, maxN));
    }

    static ArrayList<Constraint> buildConstraints(ArrayList<PointN> pts, int maxN){
        ArrayList<PointN> samp = samplePoints(pts, maxN, 0);

        double diag = bboxDiag(pts);
        double eps = 0.01 * diag;

        ArrayList<Constraint> C = new ArrayList<>();
        for(PointN pn: samp){
            Vector3 p = pn.p;
            Vector3 n = pn.n.normalize();

            
            if(n.norm() < 1e-12){
                C.add(new Constraint(p, 0.0));
                continue;
            }

            C.add(new Constraint(p, 0.0));
            C.add(new Constraint(p.add(n.mul(eps)), +1.0));
            C.add(new Constraint(p.sub(n.mul(eps)), -1.0));
        }

        System.out.println("sampled points = " + samp.size());
        System.out.println("eps = " + eps);
        System.out.println("constraints = " + C.size());
        return C;
    }

    static double phi(double r){
        
        // polyharmonic spline
        return r;
    }

    static double dist(Vector3 a, Vector3 b){
        double dx=a.x-b.x, dy=a.y-b.y, dz=a.z-b.z;
        return Math.sqrt(dx*dx+dy*dy+dz*dz);
    }

    // Solve A x = b (A will be modified). Partial pivoting Gaussian elimination.
    static double[] solveLinearSystem(double[][] A, double[] b){
        int n = b.length;
        double[] x = new double[n];

        for(int k=0;k<n;k++){
            // pivot
            int piv = k;
            double best = Math.abs(A[k][k]);
            for(int i=k+1;i<n;i++){
                double v = Math.abs(A[i][k]);
                if(v > best){ best=v; piv=i; }
            }
            if(best < 1e-12){
                throw new RuntimeException("Singular / ill-conditioned matrix at k=" + k);
            }
            if(piv != k){
                double[] tmpR = A[k]; A[k] = A[piv]; A[piv] = tmpR;
                double tmpB = b[k]; b[k] = b[piv]; b[piv] = tmpB;
            }

            // elimination
            double akk = A[k][k];
            for(int i=k+1;i<n;i++){
                double factor = A[i][k] / akk;
                if(factor == 0.0) continue;
                A[i][k] = 0.0;
                for(int j=k+1;j<n;j++){
                    A[i][j] -= factor * A[k][j];
                }
                b[i] -= factor * b[k];
            }
        }

        // back substitution
        for(int i=n-1;i>=0;i--){
            double s = b[i];
            for(int j=i+1;j<n;j++){
                s -= A[i][j] * x[j];
            }
            x[i] = s / A[i][i];
        }
        return x;
    }

    static double[] fitRBF(ArrayList<Constraint> C){
        int m = C.size();
        double[][] A = new double[m][m];
        double[] y = new double[m];

        for(int i=0;i<m;i++){
            y[i] = C.get(i).y;
        }

        // Build matrix
        for(int i=0;i<m;i++){
            Vector3 xi = C.get(i).x;
            for(int j=0;j<m;j++){
                Vector3 xj = C.get(j).x;
                double r = dist(xi, xj);
                A[i][j] = phi(r);
            }

            A[i][i] += 1e-8;
            //if(i % 100 == 0) System.out.println("Building A: " + i + "/" + m);
        }

        System.out.println("Solving system size " + m + " ...");
        double[] lambda = solveLinearSystem(A, y);
        System.out.println("Solved.");
        return lambda;
    }

    static double evalRBF(ArrayList<Constraint> C, double[] lambda, Vector3 x){
        double s = 0.0;
        for(int j=0;j<C.size();j++){
            double r = dist(x, C.get(j).x);
            s += lambda[j] * phi(r);
        }
        return s;
    }

    // ====== BBox ======
    static class BBox {
        Vector3 min, max;

        BBox(Vector3 min, Vector3 max){ this.min=min; this.max=max; }
    }

    static BBox bbox(ArrayList<PointN> pts){
        double minx=1e100, miny=1e100, minz=1e100;
        double maxx=-1e100, maxy=-1e100, maxz=-1e100;

        for(PointN pn: pts){
            Vector3 p = pn.p;

            minx=Math.min(minx,p.x); miny=Math.min(miny,p.y); minz=Math.min(minz,p.z);
            maxx=Math.max(maxx,p.x); maxy=Math.max(maxy,p.y); maxz=Math.max(maxz,p.z);
        }
        return new BBox(new Vector3(minx,miny,minz), new Vector3(maxx,maxy,maxz));
    }

    static BBox expand(BBox b, double ratio){
        Vector3 c = new Vector3((b.min.x+b.max.x)/2.0, (b.min.y+b.max.y)/2.0, (b.min.z+b.max.z)/2.0);
        Vector3 e = new Vector3((b.max.x-b.min.x)/2.0, (b.max.y-b.min.y)/2.0, (b.max.z-b.min.z)/2.0);
        e = e.mul(1.0 + ratio);
        return new BBox(c.sub(e), c.add(e));
    }

    static class ScalarField {
        int nx, ny, nz;
        BBox box;
        double[] val; // size nx*ny*nz
        ScalarField(int nx,int ny,int nz, BBox box){
            this.nx=nx; this.ny=ny; this.nz=nz; this.box=box;
            val = new double[nx*ny*nz];
        }
        int idx(int i,int j,int k){ return (k*ny + j)*nx + i; }
        Vector3 pos(int i,int j,int k){
            double tx = (nx==1)?0:(i/(double)(nx-1));
            double ty = (ny==1)?0:(j/(double)(ny-1));
            double tz = (nz==1)?0:(k/(double)(nz-1));
            return new Vector3(
                box.min.x + tx*(box.max.x - box.min.x),
                box.min.y + ty*(box.max.y - box.min.y),
                box.min.z + tz*(box.max.z - box.min.z)
            );
        }
    }

    static ScalarField buildField(ArrayList<Constraint> C, double[] lambda, BBox box, int res){
        ScalarField f = new ScalarField(res, res, res, box);
        double mn = 1e100, mx = -1e100;

        for(int k=0;k<res;k++){
            if(k % 8 == 0) System.out.println("field z-slice " + k + "/" + (res-1));
            for(int j=0;j<res;j++){
                for(int i=0;i<res;i++){
                    Vector3 x = f.pos(i,j,k);
                    double v = evalRBF(C, lambda, x);
                    f.val[f.idx(i,j,k)] = v;
                    mn = Math.min(mn, v);
                    mx = Math.max(mx, v);
                }
            }
        }
        System.out.println("Field min=" + mn + " max=" + mx + " (should straddle 0)");
        return f;
    }

    static Vector3 lerp(Vector3 a, Vector3 b, double t){
        return new Vector3(a.x + (b.x-a.x)*t, a.y + (b.y-a.y)*t, a.z + (b.z-a.z)*t);
    }

    static Vector3 vertexInterp(double iso, Vector3 p1, Vector3 p2, double v1, double v2){
        double d = (v2 - v1);
        if (Math.abs(d) < 1e-12) return p1;
        double t = (iso - v1) / d;
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        return lerp(p1, p2, t);
    }

    static Mesh marchingCubes(ScalarField field, double iso){
        Mesh mesh = new Mesh();
        int nx = field.nx, ny = field.ny, nz = field.nz;

        for(int k=0;k<nz-1;k++){
            if(k % 4 == 0) System.out.println("MC z " + k + "/" + (nz-2));
            for(int j=0;j<ny-1;j++){
                for(int i=0;i<nx-1;i++){

                    Vector3[] p = new Vector3[8];
                    double[] val = new double[8];

                    for(int c=0;c<8;c++){
                        int[] o = VERT_OFF[c];
                        int ii = i + o[0], jj = j + o[1], kk = k + o[2];
                        p[c] = field.pos(ii, jj, kk);
                        val[c] = field.val[field.idx(ii, jj, kk)];
                    }

                    int cubeindex = 0;
                    for(int c=0;c<8;c++){
                        if(val[c] < iso) cubeindex |= (1<<c);
                    }

                    int edges = MCTables.edgeTable[cubeindex];
                    if(edges == 0) continue;

                    Vector3[] vertList = new Vector3[12];
                    for(int e=0;e<12;e++){
                        if((edges & (1<<e)) != 0){
                            int a = EDGE_VERT[e][0];
                            int b = EDGE_VERT[e][1];
                            vertList[e] = vertexInterp(iso, p[a], p[b], val[a], val[b]);
                        }
                    }

                    int[] tri = MCTables.triTable[cubeindex];
                    for(int t=0; t<16; t+=3){
                        int e0 = tri[t];
                        if(e0 == -1) break;
                        int e1 = tri[t+1];
                        int e2 = tri[t+2];

                        Vector3 v0 = vertList[e0];
                        Vector3 v1 = vertList[e1];
                        Vector3 v2 = vertList[e2];

                        int id0 = mesh.V.size(); mesh.V.add(v0);
                        int id1 = mesh.V.size(); mesh.V.add(v1);
                        int id2 = mesh.V.size(); mesh.V.add(v2);
                        mesh.F.add(new int[]{id0,id1,id2});
                    }
                }
            }
        }

        System.out.println("Mesh: V=" + mesh.V.size() + " F=" + mesh.F.size());
        return mesh;
    }

    static ArrayList<PointN> loadXYZ(String path) throws IOException {
        ArrayList<PointN> pts = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while((line = br.readLine()) != null){
                line = line.trim();
                if(line.isEmpty() || line.startsWith("#")) continue;

                String[] t = line.split("\\s+");
                if(t.length < 3) continue;

                double x = Double.parseDouble(t[0]);
                double y = Double.parseDouble(t[1]);
                double z = Double.parseDouble(t[2]);
                double nx = Double.parseDouble(t[3]);
                double ny = Double.parseDouble(t[4]);
                double nz = Double.parseDouble(t[5]);

                Vector3 p = new Vector3(x,y,z);
                Vector3 n = new Vector3(nx,ny,nz);
                pts.add(new PointN(p,n));
            }
        }
        return pts;
    }
}