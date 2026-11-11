import * as THREE from 'three';

/**
 * Stand-in for an img2threejs factory, used by `npm run selftest` to prove the
 * bake path (TypeScript → THREE.Group → .glb) works without needing a full
 * eight-pass sculpt run. Mirrors the real contract: same name shape
 * (`create*Model`), takes the spec, returns a THREE.Group.
 */
export function createSelfTestModel(spec: { size?: number }, _options: unknown): THREE.Group {
  const group = new THREE.Group();
  group.name = 'SelfTest';

  const size = spec.size ?? 1;
  const body = new THREE.Mesh(
    new THREE.BoxGeometry(size, size, size),
    new THREE.MeshStandardMaterial({ color: 0xc86432, roughness: 0.7 }),
  );
  body.name = 'body';
  group.add(body);

  const cap = new THREE.Mesh(
    new THREE.CylinderGeometry(size * 0.3, size * 0.3, size * 0.4, 16),
    new THREE.MeshStandardMaterial({ color: 0x3a5f8a, metalness: 0.4 }),
  );
  cap.name = 'cap';
  cap.position.y = size * 0.7;
  group.add(cap);

  return group;
}
