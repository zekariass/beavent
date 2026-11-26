/** A place's photo (the smallest size that is sharp enough), or its first letter on a quiet surface. */
export function PlaceImage({
  url,
  variants,
  name,
  className,
  size = "w320",
}: {
  url?: string;
  variants: Record<string, string>;
  name: string;
  className: string;
  size?: "w320" | "w960";
}) {
  const src = variants[size] ?? url;
  return src ? (
    // eslint-disable-next-line @next/next/no-img-element -- public CDN images already resized by the files pipeline
    <img src={src} alt="" loading="lazy" className={`${className} bg-surface object-cover`} />
  ) : (
    <span
      aria-hidden="true"
      className={`${className} bv-type-title2 flex items-center justify-center bg-surface text-fg-muted`}
    >
      {name.slice(0, 1)}
    </span>
  );
}
