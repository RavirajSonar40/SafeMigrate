import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  typescript: {
    // TypeScript is already verified in CI; skip inside container to accelerate VM builds
    ignoreBuildErrors: true,
  },
  experimental: {
    cpus: 2,
  },
};

export default nextConfig;
