package net.vanfek.proxybans.service.domain.model;

public enum BanType {

    TEMPORARY,
    PERMANENT;

    public static BanType from(boolean permanent) {
        return permanent ? PERMANENT : TEMPORARY;
    }

}
