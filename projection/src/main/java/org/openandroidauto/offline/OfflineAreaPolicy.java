// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline;

/** Conservative public-provider bounds. Independent of Android and the map SDK. */
public final class OfflineAreaPolicy {
    public static final int MAX_ZOOM = 14;
    public static final long MAX_ESTIMATED_TILES = 2400;
    public static final long SOFT_PAUSE_BYTES = 45_000_000;
    public static final int MAX_AREAS = 64;
    public static final int[] WIDTH_KM = {1, 2, 5, 10, 20};
    public static final class Bounds {
        public final double north, east, south, west;
        Bounds(double north, double east, double south, double west) {
            this.north=north;this.east=east;this.south=south;this.west=west;
        }
        public double latitude() { return (north+south)/2; }
        public double longitude() { return (east+west)/2; }
    }
    private OfflineAreaPolicy() {}

    public static Bounds square(double latitude, double longitude, int widthKm) {
        boolean allowed=false;
        for(int width:WIDTH_KM)if(widthKm==width)allowed=true;
        if(!allowed)throw new IllegalArgumentException("Choose one of the small area sizes");
        if(!Double.isFinite(latitude)||!Double.isFinite(longitude)||Math.abs(latitude)>80||Math.abs(longitude)>180)
            throw new IllegalArgumentException("Use a latitude between -80 and 80 and longitude between -180 and 180");
        double halfLatitude=widthKm/(2*111.19508);
        double halfLongitude=halfLatitude/Math.cos(Math.toRadians(latitude));
        return validate(latitude+halfLatitude,longitude+halfLongitude,latitude-halfLatitude,longitude-halfLongitude);
    }
    public static Bounds validate(double north,double east,double south,double west) {
        if(!Double.isFinite(north)||!Double.isFinite(east)||!Double.isFinite(south)||!Double.isFinite(west)
                ||north<=south||east<=west||north>80.5||south< -80.5||east>180||west< -180
                ||(north-south)*111.19508>21)
            throw new IllegalArgumentException("Choose a small rectangle away from the poles or date line");
        double width=(east-west)*111.19508*Math.cos(Math.toRadians((north+south)/2));
        if(width>21)throw new IllegalArgumentException("Choose a smaller map area");
        return new Bounds(north,east,south,west);
    }
    /**
     * Both tile-source slots and both sprite densities are counted, even though the raster
     * source stops at zoom 6 and most vector tiles are shared between density variants.
     * One surrounding tile on each edge avoids optimistic boundary rounding.
     */
    public static long estimatedTiles(Bounds bounds) {
        validate(bounds.north,bounds.east,bounds.south,bounds.west);
        long total=0;
        for(int zoom=0;zoom<=MAX_ZOOM;zoom++) {
            long n=1L<<zoom;
            long firstX=Math.max(0,(long)Math.floor(x(bounds.west,n))-1);
            long lastX=Math.min(n-1,(long)Math.floor(x(bounds.east,n))+1);
            long firstY=Math.max(0,(long)Math.floor(y(bounds.north,n))-1);
            long lastY=Math.min(n-1,(long)Math.floor(y(bounds.south,n))+1);
            total+=4*(lastX-firstX+1)*(lastY-firstY+1);
        }
        return total;
    }
    public static void requireDownloadable(Bounds bounds) {
        if(estimatedTiles(bounds)>MAX_ESTIMATED_TILES)
            throw new IllegalArgumentException("Zoom in: this view needs too many tiles");
    }
    public static double widthKilometers(Bounds bounds) {
        return (bounds.east-bounds.west)*111.19508*Math.cos(Math.toRadians(bounds.latitude()));
    }
    public static double heightKilometers(Bounds bounds) { return (bounds.north-bounds.south)*111.19508; }
    /** Allows sub-millimeter-ish native JSON/wrapping round-off, never a different map area. */
    public static boolean matchesNativeBounds(Bounds expected,double north,double east,double south,double west) {
        return sameCoordinate(expected.north,north)&&sameCoordinate(expected.east,east)
                &&sameCoordinate(expected.south,south)&&sameCoordinate(expected.west,west);
    }
    private static boolean sameCoordinate(double expected,double actual) {
        return Double.isFinite(actual)&&Math.abs(expected-actual)<=1e-8;
    }
    private static double x(double longitude,long n) { return (longitude+180)/360*n; }
    private static double y(double latitude,long n) {
        double radians=Math.toRadians(latitude);
        return (1-Math.log(Math.tan(radians)+1/Math.cos(radians))/Math.PI)/2*n;
    }
}
