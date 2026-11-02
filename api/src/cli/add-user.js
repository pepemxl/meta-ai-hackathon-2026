'use strict';

// npm run user:add -- coach@example.com "Nombre" 'contraseña'
// npm run user:add -- --demo demo@example.com "Demo" 'contraseña'   (demo account: no upload, no Meta credit)
// Creates a coach in the MySQL database of .env (there is no public sign-up).

const { loadEnv, configFromEnv } = require('../config');
const { hashPassword } = require('../auth');
const { createMysqlRepo } = require('../repo/mysql');

async function main() {
  const args = process.argv.slice(2);
  const demo = args[0] === '--demo';
  const [email, name, password] = demo ? args.slice(1) : args;
  if (!email || !name || !password) {
    console.error('Uso: npm run user:add -- correo "Nombre" \'contraseña\'');
    process.exit(2);
  }
  if (password.length < 10) {
    console.error('La contraseña debe tener al menos 10 caracteres');
    process.exit(2);
  }
  loadEnv();
  const repo = createMysqlRepo(configFromEnv().db);
  try {
    const id = await repo.createUser({ email, name, passwordHash: hashPassword(password), demo });
    console.log(`Usuario ${demo ? 'demo ' : ''}${email} creado (coach_id u${id})`);
  } catch (e) {
    console.error(e.code === 'DUPLICATE' ? `Ya existe un usuario con ${email}` : `No se pudo crear: ${e.message}`);
    process.exitCode = 1;
  } finally {
    await repo.close();
  }
}

main();
