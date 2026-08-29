/** @type {import('next').NextConfig} */
const nextConfig = {
  output: "standalone",
  reactStrictMode: true,
  eslint: { ignoreDuringBuilds: true },
  async rewrites() {
    const target = process.env.API_BASE_URL_INTERNAL || "http://localhost:8080";
    return [{ source: "/proxy/:path*", destination: `${target}/:path*` }];
  },
};

export default nextConfig;
