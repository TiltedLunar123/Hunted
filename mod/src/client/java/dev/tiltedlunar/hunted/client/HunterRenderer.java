package dev.tiltedlunar.hunted.client;

import dev.tiltedlunar.hunted.Hunted;
import dev.tiltedlunar.hunted.hunter.HunterEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;

/**
 * Draws the hunter.
 *
 * <p>It reuses the standard humanoid mesh rather than defining its own. The
 * silhouette of a player is exactly the read we want: at fifty blocks you
 * should think someone is walking toward you, and only realise what it is when
 * the eyes resolve.
 *
 * <p>Armour is layered on top, so the tier's gear is visible and a hunter that
 * crafted its own iron in survival mode looks different from one that has not
 * found any yet.
 */
public class HunterRenderer
		extends HumanoidMobRenderer<HunterEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {
	private static final Identifier TEXTURE =
			Identifier.fromNamespaceAndPath(Hunted.MOD_ID, "textures/entity/hunter.png");

	public HunterRenderer(EntityRendererProvider.Context context) {
		super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5f);
		this.addLayer(new HumanoidArmorLayer<>(
				this,
				ArmorModelSet.bake(ModelLayers.ZOMBIE_ARMOR, context.getModelSet(), HumanoidModel::new),
				ArmorModelSet.bake(ModelLayers.ZOMBIE_BABY_ARMOR, context.getModelSet(), HumanoidModel::new),
				context.getEquipmentRenderer()));
	}

	@Override
	public HumanoidRenderState createRenderState() {
		return new HumanoidRenderState();
	}

	@Override
	public Identifier getTextureLocation(HumanoidRenderState state) {
		return TEXTURE;
	}
}
