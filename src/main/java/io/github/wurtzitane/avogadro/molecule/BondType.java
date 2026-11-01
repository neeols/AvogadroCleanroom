package io.github.wurtzitane.avogadro.molecule;

/** Represents chemical bond types with their respective bond orders and visual rendering radii. */
public enum BondType {

    /** Single chemical bond. */
    SINGLE(1, 0.15f),

    /** Double chemical bond. */
    DOUBLE(2, 0.15f),

    /** Triple chemical bond. */
    TRIPLE(3, 0.15f),

    /** Quadruple chemical bond. */
    QUADRUPLE(4, 0.15f);

    /** Numerical bond order indicating the number of shared electron pairs. */
    private final int order;

    /** Visual cylinder radius used for rendering the bond. */
    private final float radius;

    /**
     * Constructs a bond type with the specified bond order and radius.
     *
     * @param order  bond order integer
     * @param radius rendering radius float
     */
    BondType(int order, float radius) {
        this.order = order;
        this.radius = radius;
    }

    /**
     * Retrieves the numerical bond order.
     *
     * @return bond order integer
     */
    public int order() {
        return order;
    }

    /**
     * Retrieves the visual rendering radius.
     *
     * @return bond radius float
     */
    public float radius() {
        return radius;
    }
}