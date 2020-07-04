import resolve from '@rollup/plugin-node-resolve';
import commonjs from '@rollup/plugin-commonjs';
import typescript from 'rollup-plugin-typescript2';
import scss from 'rollup-plugin-scss';
import copy from 'rollup-plugin-copy';
import { terser } from 'rollup-plugin-terser';

export default {
    input: 'src/main.ts',
    output: { dir: 'dist', format: 'umd' },
    watch: { include: 'src/**' },
    plugins: [
        typescript(),
        commonjs(),
        resolve(),
        terser(),
        scss({
          output: 'dist/main.css',
          outputStyle: 'compressed',
        }),
        copy({
            targets: [
                { src: "src/static/*.html", dest: "dist/" },
                { src: "src/static/*.css", dest: "dist/" }
            ]
        })]
};
