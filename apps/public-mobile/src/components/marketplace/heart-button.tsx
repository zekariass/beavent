import { Icon, useApp } from "@beavent/native";
import { size } from "@beavent/ui";
import { Pressable } from "react-native";

/** The heart (C-3): saves a place or takes it off the list. 48 dp, with a translated label naming the place. */
export function HeartButton({
  name,
  saved,
  onPress,
  onImage,
}: {
  name: string;
  saved: boolean;
  onPress: () => void;
  /** Sits on a photo: a solid circle so it reads on any image. */
  onImage?: boolean;
}) {
  const { t, colors } = useApp();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ selected: saved }}
      accessibilityLabel={`${saved ? t("save.remove") : t("save.add")}: ${name}`}
      onPress={onPress}
      style={({ pressed }) => ({
        width: size.touchTarget,
        height: size.touchTarget,
        borderRadius: size.touchTarget / 2,
        alignItems: "center",
        justifyContent: "center",
        backgroundColor: onImage
          ? colors.background
          : pressed
            ? colors.surfacePressed
            : "transparent",
      })}
    >
      <Icon name={saved ? "heartFilled" : "heart"} size="lg" tone={saved ? "primary" : "text"} />
    </Pressable>
  );
}
