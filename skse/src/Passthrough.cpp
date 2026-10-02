#include "Game.h"

#include <d3dcompiler.h>

namespace skycraft::Passthrough
{
	namespace
	{
		constexpr wchar_t kMappingName[] = L"Local\\SkyCraftFrame_v1";
		constexpr std::uint32_t kMagic = 0x46594B53; // "SKYF"
		constexpr std::uint32_t kVersion = 1;
		constexpr std::uint64_t kHeaderBytes = 4096;
		constexpr std::uint32_t kSlotCount = 3;
		constexpr std::uint64_t kSlotDesc = 256;
		constexpr std::uint64_t kSlotDescBytes = 128;
		constexpr std::uint32_t kMaxW = 3840;
		constexpr std::uint32_t kMaxH = 2160;
		constexpr std::uint64_t kLayerMax = std::uint64_t(kMaxW) * kMaxH * 4;
		constexpr std::uint64_t kSlotStride = kLayerMax * 3;

		HANDLE mapping = nullptr;
		const std::uint8_t* view = nullptr;
		DWORD nextOpenAttempt = 0;
		std::uint64_t lastPublish = 0;
		bool haveFrame = false;

		ID3D11Device* device = nullptr;
		ID3D11Texture2D* worldTex = nullptr;
		ID3D11ShaderResourceView* worldSrv = nullptr;
		ID3D11Texture2D* mcDepthTex = nullptr;
		ID3D11ShaderResourceView* mcDepthSrv = nullptr;
		ID3D11Texture2D* overlayTex = nullptr;
		ID3D11ShaderResourceView* overlaySrv = nullptr;
		UINT texW = 0, texH = 0;

		ID3D11VertexShader* vs = nullptr;
		ID3D11PixelShader* worldPs = nullptr;
		ID3D11PixelShader* overlayPs = nullptr;
		ID3D11SamplerState* sampler = nullptr;
		ID3D11BlendState* blend = nullptr;
		ID3D11RasterizerState* raster = nullptr;
		ID3D11DepthStencilState* noDepth = nullptr;
		ID3D11Buffer* params = nullptr;
		bool initFailed = false;

		float mcNear = 0.05f;
		float mcFar = 1024.0f;
		float mcFov = 70.0f;
		bool flipY = true;

		template <class T>
		void Release(T*& a_ptr)
		{
			if (a_ptr) {
				a_ptr->Release();
				a_ptr = nullptr;
			}
		}

		template <class T>
		T Read(const std::uint8_t* a_p)
		{
			T value{};
			std::memcpy(&value, a_p, sizeof(T));
			return value;
		}

		struct alignas(16) Params
		{
			float mcPlanes[4];      // near, far, flipY, unused
			float skyPlanes[4];     // near, far, reversed, depthEnabled
			float misc[4];          // 1 / Skyrim-units-per-block, depth bias in blocks, -, -
		};

		constexpr char kShader[] = R"(
cbuffer Params : register(b0)
{
	float4 mcPlanes;
	float4 skyPlanes;
	float4 misc;
};
Texture2D mcWorld : register(t0);
Texture2D<float> mcDepth : register(t1);
Texture2D<float> skyDepth : register(t2);
Texture2D mcOverlay : register(t3);
SamplerState pointSampler : register(s0);

struct VSOut { float4 pos : SV_Position; float2 uv : TEXCOORD0; };
VSOut VSMain(uint id : SV_VertexID)
{
	VSOut o;
	float2 uv = float2((id << 1) & 2, id & 2);
	o.pos = float4(uv * float2(2, -2) + float2(-1, 1), 0, 1);
	o.uv = uv;
	return o;
}

float LinearStandard(float d, float n, float f)
{
	return n * f / max(f - d * (f - n), 1e-6);
}

float LinearReversed(float d, float n, float f)
{
	return n * f / max(n + d * (f - n), 1e-6);
}

float2 McUv(float2 uv)
{
	if (mcPlanes.z > 0.5) uv.y = 1.0 - uv.y;
	return uv;
}

float4 PSWorld(VSOut i) : SV_Target
{
	float2 muv = McUv(i.uv);
	float4 c = mcWorld.SampleLevel(pointSampler, muv, 0);
	if (c.a <= 0.001) discard;

	float md = mcDepth.SampleLevel(pointSampler, muv, 0);
	if (md >= 0.999999) discard;

	if (skyPlanes.w > 0.5) {
		float sd = skyDepth.SampleLevel(pointSampler, i.uv, 0);
		float mcZ = LinearStandard(md, mcPlanes.x, mcPlanes.y);
		float skyUnits = skyPlanes.z > 0.5
			? LinearReversed(sd, skyPlanes.x, skyPlanes.y)
			: LinearStandard(sd, skyPlanes.x, skyPlanes.y);
		float skyBlocks = skyUnits * misc.x;
		if (mcZ > skyBlocks + misc.y) discard;
	}
	return c;
}

float4 PSOverlay(VSOut i) : SV_Target
{
	return mcOverlay.SampleLevel(pointSampler, McUv(i.uv), 0);
}
)";

		bool Compile(const char* a_entry, const char* a_target, ID3DBlob** a_out)
		{
			ID3DBlob* errors = nullptr;
			const auto hr = D3DCompile(kShader, sizeof(kShader) - 1, "skycraft_passthrough", nullptr, nullptr,
				a_entry, a_target, D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, a_out, &errors);
			if (FAILED(hr)) {
				logger::error("passthrough shader {} failed: {}", a_entry,
					errors ? static_cast<const char*>(errors->GetBufferPointer()) : "?");
				Release(errors);
				return false;
			}
			Release(errors);
			return true;
		}

		bool Init(ID3D11Device* a_device)
		{
			if (device) {
				return true;
			}
			if (initFailed || !a_device) {
				return false;
			}
			device = a_device;
			device->AddRef();

			ID3DBlob *vsBlob = nullptr, *wpBlob = nullptr, *opBlob = nullptr;
			if (!Compile("VSMain", "vs_5_0", &vsBlob) ||
				!Compile("PSWorld", "ps_5_0", &wpBlob) ||
				!Compile("PSOverlay", "ps_5_0", &opBlob)) {
				initFailed = true;
				Release(vsBlob);
				Release(wpBlob);
				Release(opBlob);
				return false;
			}
			device->CreateVertexShader(vsBlob->GetBufferPointer(), vsBlob->GetBufferSize(), nullptr, &vs);
			device->CreatePixelShader(wpBlob->GetBufferPointer(), wpBlob->GetBufferSize(), nullptr, &worldPs);
			device->CreatePixelShader(opBlob->GetBufferPointer(), opBlob->GetBufferSize(), nullptr, &overlayPs);
			Release(vsBlob);
			Release(wpBlob);
			Release(opBlob);

			D3D11_SAMPLER_DESC sd{};
			sd.Filter = D3D11_FILTER_MIN_MAG_MIP_POINT;
			sd.AddressU = sd.AddressV = sd.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
			sd.MaxLOD = D3D11_FLOAT32_MAX;
			device->CreateSamplerState(&sd, &sampler);

			D3D11_BLEND_DESC bd{};
			bd.RenderTarget[0].BlendEnable = TRUE;
			bd.RenderTarget[0].SrcBlend = D3D11_BLEND_ONE;
			bd.RenderTarget[0].DestBlend = D3D11_BLEND_INV_SRC_ALPHA;
			bd.RenderTarget[0].BlendOp = D3D11_BLEND_OP_ADD;
			bd.RenderTarget[0].SrcBlendAlpha = D3D11_BLEND_ONE;
			bd.RenderTarget[0].DestBlendAlpha = D3D11_BLEND_INV_SRC_ALPHA;
			bd.RenderTarget[0].BlendOpAlpha = D3D11_BLEND_OP_ADD;
			bd.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
			device->CreateBlendState(&bd, &blend);

			D3D11_RASTERIZER_DESC rd{};
			rd.FillMode = D3D11_FILL_SOLID;
			rd.CullMode = D3D11_CULL_NONE;
			rd.DepthClipEnable = TRUE;
			device->CreateRasterizerState(&rd, &raster);

			D3D11_DEPTH_STENCIL_DESC dd{};
			dd.DepthEnable = FALSE;
			dd.StencilEnable = FALSE;
			device->CreateDepthStencilState(&dd, &noDepth);

			D3D11_BUFFER_DESC cb{};
			cb.ByteWidth = sizeof(Params);
			cb.Usage = D3D11_USAGE_DYNAMIC;
			cb.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
			cb.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
			device->CreateBuffer(&cb, nullptr, &params);

			const bool ok = vs && worldPs && overlayPs && sampler && blend && raster && noDepth && params;
			logger::info("framebuffer passthrough renderer {}", ok ? "ready" : "failed to initialize");
			initFailed = !ok;
			return ok;
		}

		bool OpenMapping()
		{
			if (view) {
				return true;
			}
			const DWORD now = ::GetTickCount();
			if (now < nextOpenAttempt) {
				return false;
			}
			nextOpenAttempt = now + 1000;

			mapping = ::OpenFileMappingW(FILE_MAP_READ, FALSE, kMappingName);
			if (!mapping) {
				return false;
			}
			view = static_cast<const std::uint8_t*>(::MapViewOfFile(mapping, FILE_MAP_READ, 0, 0, 0));
			if (!view || Read<std::uint32_t>(view) != kMagic || Read<std::uint32_t>(view + 4) != kVersion) {
				if (view) {
					::UnmapViewOfFile(view);
					view = nullptr;
				}
				::CloseHandle(mapping);
				mapping = nullptr;
				return false;
			}
			logger::info("framebuffer passthrough: connected to {}", "Local\\SkyCraftFrame_v1");
			return true;
		}

		bool EnsureTextures(UINT a_w, UINT a_h)
		{
			if (worldTex && texW == a_w && texH == a_h) {
				return true;
			}
			Release(worldSrv);
			Release(worldTex);
			Release(mcDepthSrv);
			Release(mcDepthTex);
			Release(overlaySrv);
			Release(overlayTex);

			auto make = [&](DXGI_FORMAT a_format, ID3D11Texture2D** a_tex, ID3D11ShaderResourceView** a_srv) {
				D3D11_TEXTURE2D_DESC td{};
				td.Width = a_w;
				td.Height = a_h;
				td.MipLevels = 1;
				td.ArraySize = 1;
				td.Format = a_format;
				td.SampleDesc.Count = 1;
				td.Usage = D3D11_USAGE_DYNAMIC;
				td.BindFlags = D3D11_BIND_SHADER_RESOURCE;
				td.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
				return SUCCEEDED(device->CreateTexture2D(&td, nullptr, a_tex)) &&
				       SUCCEEDED(device->CreateShaderResourceView(*a_tex, nullptr, a_srv));
			};

			if (!make(DXGI_FORMAT_R8G8B8A8_UNORM, &worldTex, &worldSrv) ||
				!make(DXGI_FORMAT_R32_FLOAT, &mcDepthTex, &mcDepthSrv) ||
				!make(DXGI_FORMAT_R8G8B8A8_UNORM, &overlayTex, &overlaySrv)) {
				logger::error("framebuffer passthrough: texture creation failed for {}x{}", a_w, a_h);
				return false;
			}
			texW = a_w;
			texH = a_h;
			logger::info("framebuffer passthrough textures {}x{}", a_w, a_h);
			return true;
		}

		bool Upload(ID3D11DeviceContext* a_context, ID3D11Texture2D* a_tex,
			const std::uint8_t* a_src, UINT a_w, UINT a_h)
		{
			D3D11_MAPPED_SUBRESOURCE mapped{};
			if (FAILED(a_context->Map(a_tex, 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped))) {
				return false;
			}
			const UINT rowBytes = a_w * 4;
			auto* dst = static_cast<std::uint8_t*>(mapped.pData);
			if (mapped.RowPitch == rowBytes) {
				std::memcpy(dst, a_src, std::size_t(rowBytes) * a_h);
			} else {
				for (UINT y = 0; y < a_h; ++y) {
					std::memcpy(dst + std::size_t(y) * mapped.RowPitch,
						a_src + std::size_t(y) * rowBytes, rowBytes);
				}
			}
			a_context->Unmap(a_tex, 0);
			return true;
		}

		bool UpdateFrame(ID3D11DeviceContext* a_context)
		{
			if (!OpenMapping()) {
				return false;
			}
			const auto publish = Read<std::uint64_t>(view + 32);
			if (publish == 0 || publish == lastPublish) {
				return haveFrame;
			}
			const auto slot = Read<std::int32_t>(view + 40);
			if (slot < 0 || slot >= static_cast<std::int32_t>(kSlotCount)) {
				return haveFrame;
			}

			const auto* desc = view + kSlotDesc + kSlotDescBytes * slot;
			const auto seq1 = Read<std::uint64_t>(desc);
			::MemoryBarrier();
			if (seq1 & 1u) {
				return haveFrame;
			}
			const UINT w = Read<std::uint32_t>(desc + 16);
			const UINT h = Read<std::uint32_t>(desc + 20);
			const auto flags = Read<std::uint32_t>(desc + 24);
			if (!w || !h || w > kMaxW || h > kMaxH || !EnsureTextures(w, h)) {
				return haveFrame;
			}

			const std::uint64_t bytes = std::uint64_t(w) * h * 4;
			const auto* base = view + kHeaderBytes + kSlotStride * slot;
			if (!Upload(a_context, worldTex, base, w, h) ||
				!Upload(a_context, mcDepthTex, base + bytes, w, h) ||
				!Upload(a_context, overlayTex, base + bytes * 2, w, h)) {
				return haveFrame;
			}

			::MemoryBarrier();
			const auto seq2 = Read<std::uint64_t>(desc);
			if (seq1 != seq2 || (seq2 & 1u)) {
				haveFrame = false;
				return false;
			}

			mcNear = Read<float>(desc + 32);
			mcFar = Read<float>(desc + 36);
			mcFov = Read<float>(desc + 40);
			flipY = (flags & 1u) != 0;
			lastPublish = publish;
			haveFrame = true;

			static bool logged = false;
			if (!logged) {
				logged = true;
				logger::info("framebuffer passthrough: receiving world+depth+overlay, MC near {:.3f} far {:.1f} FOV {:.1f}",
					mcNear, mcFar, mcFov);
			}
			return true;
		}

		struct StateBackup
		{
			ID3D11RenderTargetView* rtv[D3D11_SIMULTANEOUS_RENDER_TARGET_COUNT]{};
			ID3D11DepthStencilView* dsv = nullptr;
			ID3D11BlendState* blend = nullptr;
			float factor[4]{};
			UINT mask = 0;
			ID3D11RasterizerState* raster = nullptr;
			ID3D11DepthStencilState* depth = nullptr;
			UINT stencil = 0;
			D3D11_VIEWPORT vps[D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE]{};
			UINT vpCount = D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE;
			D3D11_PRIMITIVE_TOPOLOGY topo{};
			ID3D11InputLayout* layout = nullptr;
			ID3D11VertexShader* oldVs = nullptr;
			ID3D11PixelShader* oldPs = nullptr;
			ID3D11ShaderResourceView* srv[4]{};
			ID3D11SamplerState* oldSampler = nullptr;
			ID3D11Buffer* oldCb = nullptr;

			void Save(ID3D11DeviceContext* c)
			{
				c->OMGetRenderTargets(D3D11_SIMULTANEOUS_RENDER_TARGET_COUNT, rtv, &dsv);
				c->OMGetBlendState(&blend, factor, &mask);
				c->RSGetState(&raster);
				c->OMGetDepthStencilState(&depth, &stencil);
				c->RSGetViewports(&vpCount, vps);
				c->IAGetPrimitiveTopology(&topo);
				c->IAGetInputLayout(&layout);
				c->VSGetShader(&oldVs, nullptr, nullptr);
				c->PSGetShader(&oldPs, nullptr, nullptr);
				c->PSGetShaderResources(0, 4, srv);
				c->PSGetSamplers(0, 1, &oldSampler);
				c->PSGetConstantBuffers(0, 1, &oldCb);
			}

			void Restore(ID3D11DeviceContext* c)
			{
				c->OMSetRenderTargets(D3D11_SIMULTANEOUS_RENDER_TARGET_COUNT, rtv, dsv);
				c->OMSetBlendState(blend, factor, mask);
				c->RSSetState(raster);
				c->OMSetDepthStencilState(depth, stencil);
				c->RSSetViewports(vpCount, vps);
				c->IASetPrimitiveTopology(topo);
				c->IASetInputLayout(layout);
				c->VSSetShader(oldVs, nullptr, 0);
				c->PSSetShader(oldPs, nullptr, 0);
				c->PSSetShaderResources(0, 4, srv);
				c->PSSetSamplers(0, 1, &oldSampler);
				c->PSSetConstantBuffers(0, 1, &oldCb);
				for (auto*& x : rtv) Release(x);
				Release(dsv);
				Release(blend);
				Release(raster);
				Release(depth);
				Release(layout);
				Release(oldVs);
				Release(oldPs);
				for (auto*& x : srv) Release(x);
				Release(oldSampler);
				Release(oldCb);
			}
		};

		bool SkyrimDepthInfo(float& a_near, float& a_far, bool& a_reversed,
			ID3D11ShaderResourceView*& a_depthSrv)
		{
			auto* camera = RE::Main::WorldRootCamera();
			auto* renderer = RE::BSGraphics::Renderer::GetSingleton();
			if (!camera || !renderer) {
				return false;
			}

			const auto& frustum = camera->GetRuntimeData2().viewFrustum;
			a_near = frustum.fNear;
			a_far = frustum.fFar;
			a_depthSrv = reinterpret_cast<ID3D11ShaderResourceView*>(
				renderer->GetDepthStencilData().depthStencils[RE::RENDER_TARGETS_DEPTHSTENCIL::kMAIN].depthSRV);
			if (!a_depthSrv || a_near <= 0.0f || a_far <= a_near) {
				return false;
			}

			// Learn the convention directly from Skyrim's current world->clip matrix.
			const auto& m = camera->GetRuntimeData().worldToCam;
			const auto& r = camera->world.rotate;
			RE::NiPoint3 fwd{ r.entry[0][0], r.entry[1][0], r.entry[2][0] };
			auto ndc = [&](float distance) {
				const auto p = fwd * distance;
				const double z = m[2][0] * p.x + m[2][1] * p.y + m[2][2] * p.z + m[2][3];
				const double w = m[3][0] * p.x + m[3][1] * p.y + m[3][2] * p.z + m[3][3];
				return std::fabs(w) > 1e-9 ? z / w : 0.0;
			};
			a_reversed = ndc(100.0f) > ndc(10000.0f);
			return true;
		}
	}

	bool Draw(ID3D11Device* a_device, ID3D11DeviceContext* a_context, IDXGISwapChain* a_swapChain)
	{
		auto& state = State();
		if (!a_device || !a_context || !a_swapChain || !state.mcInWorld || state.skyrimMenuOpen ||
			!Init(a_device) || !UpdateFrame(a_context) || !haveFrame) {
			return false;
		}

		ID3D11Texture2D* back = nullptr;
		if (FAILED(a_swapChain->GetBuffer(0, __uuidof(ID3D11Texture2D), reinterpret_cast<void**>(&back)))) {
			return false;
		}
		D3D11_TEXTURE2D_DESC bb{};
		back->GetDesc(&bb);
		ID3D11RenderTargetView* rtv = nullptr;
		if (FAILED(device->CreateRenderTargetView(back, nullptr, &rtv))) {
			Release(back);
			return false;
		}
		Release(back);

		float skyNear = 0.1f, skyFar = 10000.0f;
		bool reversed = true;
		ID3D11ShaderResourceView* skyDepth = nullptr;
		const bool depthEnabled = SkyrimDepthInfo(skyNear, skyFar, reversed, skyDepth);

		D3D11_MAPPED_SUBRESOURCE mapped{};
		if (SUCCEEDED(a_context->Map(params, 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped))) {
			auto* p = static_cast<Params*>(mapped.pData);
			p->mcPlanes[0] = mcNear;
			p->mcPlanes[1] = mcFar;
			p->mcPlanes[2] = flipY ? 1.0f : 0.0f;
			p->mcPlanes[3] = 0.0f;
			p->skyPlanes[0] = skyNear;
			p->skyPlanes[1] = skyFar;
			p->skyPlanes[2] = reversed ? 1.0f : 0.0f;
			p->skyPlanes[3] = depthEnabled ? 1.0f : 0.0f;
			p->misc[0] = 1.0f / static_cast<float>(proto::kUnitsPerBlock);
			p->misc[1] = 0.06f; // about 4 Skyrim units of contact tolerance
			p->misc[2] = p->misc[3] = 0.0f;
			a_context->Unmap(params, 0);
		}

		StateBackup backup;
		backup.Save(a_context);

		ID3D11ShaderResourceView* resources[4] = { worldSrv, mcDepthSrv, depthEnabled ? skyDepth : nullptr, overlaySrv };
		D3D11_VIEWPORT vp{ 0.0f, 0.0f, static_cast<float>(bb.Width), static_cast<float>(bb.Height), 0.0f, 1.0f };
		const float factor[4]{};

		// Unbind Skyrim's depth as an output before sampling it.
		a_context->OMSetRenderTargets(1, &rtv, nullptr);
		a_context->OMSetBlendState(blend, factor, 0xFFFFFFFF);
		a_context->OMSetDepthStencilState(noDepth, 0);
		a_context->RSSetState(raster);
		a_context->RSSetViewports(1, &vp);
		a_context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
		a_context->IASetInputLayout(nullptr);
		a_context->VSSetShader(vs, nullptr, 0);
		a_context->PSSetShaderResources(0, 4, resources);
		a_context->PSSetSamplers(0, 1, &sampler);
		a_context->PSSetConstantBuffers(0, 1, &params);

		// Minecraft world, discarded wherever Skyrim has nearer geometry.
		a_context->PSSetShader(worldPs, nullptr, 0);
		a_context->Draw(3, 0);

		// Hand/HUD/screens are screen-space and always go on top.
		a_context->PSSetShader(overlayPs, nullptr, 0);
		a_context->Draw(3, 0);

		ID3D11ShaderResourceView* clear[4]{};
		a_context->PSSetShaderResources(0, 4, clear);
		backup.Restore(a_context);
		Release(rtv);

		static bool firstDraw = true;
		if (firstDraw) {
			firstDraw = false;
			logger::info("framebuffer passthrough: first Skyrim composite drawn (depth {}, {} Z)",
				depthEnabled ? "enabled" : "unavailable", reversed ? "reversed" : "standard");
		}
		return true;
	}
}
