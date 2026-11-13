package io.github.wurtzitane.avogadro.client;

import java.util.List;

import javax.annotation.Nullable;
import javax.vecmath.Matrix4f;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.EnumFacing;

import org.apache.commons.lang3.tuple.Pair;

final class PeriodicTableItemModel implements IBakedModel {
    private final IBakedModel delegate;
    private final PeriodicTableItemModel owner;
    private final PeriodicTableItemModel gui;

    PeriodicTableItemModel(IBakedModel delegate) {
        this.delegate = delegate;
        owner = this;
        gui = new PeriodicTableItemModel(delegate, this);
    }

    private PeriodicTableItemModel(IBakedModel delegate, PeriodicTableItemModel owner) {
        this.delegate = delegate;
        this.owner = owner;
        gui = this;
    }

    @Override
    public Pair<? extends IBakedModel, Matrix4f> handlePerspective(ItemCameraTransforms.TransformType type) {
        var transformed = delegate.handlePerspective(type);
        return Pair.of(type == ItemCameraTransforms.TransformType.GUI ? owner.gui : owner, transformed.getRight());
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable IBlockState state, @Nullable EnumFacing side, long random) {
        return owner == this
                ? delegate.getQuads(state, side, random)
                : side == null ? PeriodicTableIcon.quads() : List.of();
    }

    @Override
    public boolean isAmbientOcclusion() {
        return owner == this && delegate.isAmbientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return false;
    }

    @Override
    public boolean isBuiltInRenderer() {
        return owner == this;
    }

    @Override
    public TextureAtlasSprite getParticleTexture() {
        return delegate.getParticleTexture();
    }

    @Override
    public ItemCameraTransforms getItemCameraTransforms() {
        return delegate.getItemCameraTransforms();
    }

    @Override
    public ItemOverrideList getOverrides() {
        return delegate.getOverrides();
    }
}
